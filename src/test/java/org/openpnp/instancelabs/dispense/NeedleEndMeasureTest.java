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
        /** The frames kept as taken (ids 1000 on): their captions, and whether each was the image the camera gave. */
        final List<String> kept = new ArrayList<>();
        final List<Boolean> keptAsTaken = new ArrayList<>();
        java.awt.image.BufferedImage taken;
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
            taken = scene.render(x, y, z);
            return new DotImager.Frame(taken, 0.0667, 0.0656, scene.camX, scene.camY);
        }

        @Override
        public Long keepRaw(java.awt.image.BufferedImage frame, String caption, double mmPerPx) {
            kept.add(caption);
            keptAsTaken.add(frame == taken);
            return 1000L + kept.size() - 1;
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
            assertTrue(h.peakToSd >= 10, String.valueOf(h.peakToSd));
        }
        assertEquals(0.019, e.tiltXMmPerRawMm, 0.003);
        assertEquals(-0.076, e.tiltYMmPerRawMm, 0.003);
        double[] at = e.at(43.0);
        assertEquals(sc.end(43.0)[0], at[0], 0.015, "extrapolated to a press height");
        assertEquals(sc.end(43.0)[1], at[1], 0.015);
        assertEquals(65.0, e.kParallax, 0.1);
        assertTrue(e.errKMm < 0.1, "K's spread " + e.errKMm);
        assertEquals(sc.fPx, e.fPx, 0.015 * sc.fPx, "f, by N1's scales");
        assertEquals(1.0, e.needleMmPerRawMm, 0.02, "the drawn see-saw's");
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
        assertEquals(8, r.pictures.size(), "each height's centre frame, then the clean check's");
        assertTrue(r.pictures.get(7).caption.startsWith("The clean check's frame"), r.pictures.get(7).caption);
        assertEquals(43, cam.framesDone);
        assertEquals(43, cam.framesPlanned);
        assertTrue(cam.phases.get(0).startsWith("needle end: N1 at raw Z 19.000 (1 of 3)"), cam.phases.toString());
        assertEquals("needle end: the needle at raw Z 42.000 (4 of 4)", cam.phases.get(6));
        assertEquals("needle end: the clean check, the needle at raw Z 40.400", cam.phases.get(7));
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
        assertEquals(43, frame, "the heights' 42 and the clean check's");
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
     * No end and no mark: the brightest noise in the window is some 6 times its sd, under check (a)'s 10 (Process 11-05
     * 04:3x; Process's 25G ends read 11.7-18.9 times through the ±3 mm search), so the first frame is refused.
     */
    @Test
    public void noEndIsTheEndNotFound() throws Exception {
        FromBelowScene sc = new FromBelowScene(5);
        sc.needle = false;
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        refused(r, NeedleEndMeasure.NOT_FOUND, "the end was not found (light or focus): the needle at raw Z 36.000, "
                + "frame 1 of 6", cam);
        assertTrue(r.why.contains("(at least 10.0)"), r.why);
    }

    /**
     * Process 11-05 04:3x: the end is searched ±3 mm around where the tip setup puts it (searchMm, in px by the camera's
     * scale). A brighter object 5 mm from the end that moves with the head (a glint off the holder) does not take the
     * aim: the end comes out as drawn. (Here the frames are followed 30 px around the first spot: a 1 mm step moves the
     * end at most 17 px, and with np1.py's 60 the object comes within 4.35 mm, 62 px at raw 36, of the first spot in a
     * stepped frame.) An end more than 3 mm off is not found, the safe failure.
     */
    @Test
    public void aBrighterObject5mmAwayDoesNotTakeTheAim() throws Exception {
        FromBelowScene sc = new FromBelowScene(16);
        sc.brightMm = new double[] {-3.536, -3.536};
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Plan p = plan(sc, sc.endDx, sc.endDy);
        p.s.followPx = 30;
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, p);
        assertTrue(r.ok, r.why);
        for (DispenseConfig.EndHeight h : r.end.heights) {
            assertEquals(sc.end(h.rawZ)[0], h.dxMm, 0.01, "dx at " + h.rawZ);
            assertEquals(sc.end(h.rawZ)[1], h.dyMm, 0.01, "dy at " + h.rawZ);
        }
        FromBelowScene off = new FromBelowScene(17);
        Camera cam2 = new Camera(off);
        NeedleEndMeasure.Result far = NeedleEndMeasure.measure(cam2, plan(off, off.endDx - 3.5, off.endDy));
        assertEquals(NeedleEndMeasure.NOT_FOUND, far.code, far.why);
        assertNull(far.end);
    }

    /**
     * Re-review NIT 2: the default follow window, np1.py's 60 px, never aims at a brighter object either. The same
     * object 5 mm from the end stays outside it (62 px from the first spot in a stepped frame at raw 36): the end comes
     * out as drawn. 4 mm from the end (57 px at raw 36) it is inside it, and the frames that take it leave the step
     * fit: the end not found, no end (without that check the drift, then the clean check's scale, refuses it).
     */
    @Test
    public void aBrighterObjectNeverTakesTheAimAtTheDefaultFollowWindow() throws Exception {
        FromBelowScene sc = new FromBelowScene(16);
        sc.brightMm = new double[] {-3.536, -3.536};
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(new Camera(sc), plan(sc, sc.endDx, sc.endDy));
        assertTrue(r.ok, r.why);
        for (DispenseConfig.EndHeight h : r.end.heights) {
            assertEquals(sc.end(h.rawZ)[0], h.dxMm, 0.01, "dx at " + h.rawZ);
            assertEquals(sc.end(h.rawZ)[1], h.dyMm, 0.01, "dy at " + h.rawZ);
        }
        FromBelowScene near = new FromBelowScene(16);
        near.brightMm = new double[] {-2.828, -2.828};
        Camera cam = new Camera(near);
        NeedleEndMeasure.Result n = NeedleEndMeasure.measure(cam, plan(near, near.endDx, near.endDy));
        refused(n, NeedleEndMeasure.NOT_FOUND, "the needle at raw Z 36.000: the frames do not follow the head's steps",
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

    /**
     * Review MAJOR 1: needle heights without the clean check's (needleRawZ [36, 39, 42], cleanRawZ 40.4, which PUT
     * /api/config refuses but a settings file may hold) give no clean verdict, so no end: refused with no code (the
     * settings are Process's), nothing recorded, the head back at travel Z.
     */
    @Test
    public void noCleanVerdictIsNoEnd() throws Exception {
        FromBelowScene sc = new FromBelowScene(12);
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Plan p = plan(sc, sc.endDx, sc.endDy);
        p.s.needleRawZ = new ArrayList<>(java.util.Arrays.asList(36.0, 39.0, 42.0));
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, p);
        assertFalse(r.ok, "an end without a clean verdict");
        assertNull(r.end);
        assertNull(r.code, r.why);
        assertTrue(r.why.contains("the clean check did not run"), r.why);
        assertEquals("G1 Z31.500 F1200", cam.lines.get(cam.lines.size() - 2), "back at travel Z");
    }

    /**
     * Review MINOR 1: a tip kind's clean limits are all its own. An entry that leaves a limit out (another kind's, or the
     * 25G's with a misspelt key) is refused by name, also from the settings file (the measure validates it): never
     * filled in with the 25G's. The engine's copies of the settings keep what was refused.
     */
    @Test
    public void aTipKindsCleanLimitsAreAllItsOwn() {
        DispenseConfig c = new DispenseConfig();
        assertTrue(c.validate().stream().noneMatch(x -> x.contains("needleEndMeasure")), "the 25G's entry, every limit "
                + "given: " + c.validate());
        com.google.gson.JsonParser json = new com.google.gson.JsonParser();
        com.google.gson.JsonObject o = json.parse(c.toJson()).getAsJsonObject();
        com.google.gson.JsonObject clean = o.getAsJsonObject("needleEndMeasure").getAsJsonObject("clean");
        clean.add("steel-27g", json.parse("{\"spreadMax\": 0.41}"));
        com.google.gson.JsonObject g25 = clean.getAsJsonObject("plastic-25g-taper");
        g25.add("spredMax", g25.remove("spreadMax"));
        DispenseConfig file = DispenseConfig.fromJson(o.toString());
        List<String> p = file.validate();
        assertTrue(p.stream().anyMatch(x -> x.startsWith("needleEndMeasure.clean['steel-27g']: no rimMin")),
                p.toString());
        assertTrue(p.stream().anyMatch(x -> x.startsWith("needleEndMeasure.clean['plastic-25g-taper']: no spreadMax")
                && x.contains("'spredMax' is no limit")), p.toString());
        assertEquals(p, file.copy().validate(), "a copy keeps them");
    }

    /**
     * Review MINOR 8: the clean check reads one more frame, the measure's last: the needle at cleanRawZ over B
     * (approached from (−0.5, −0.5)) after every other height, then only the lift. Paste that comes on after the heights'
     * frames (a drip during the measure) is caught there.
     */
    @Test
    public void theCleanCheckReadsTheLastFrame() throws Exception {
        FromBelowScene sc = new FromBelowScene(13);
        Camera cam = new Camera(sc) {
            @Override
            public DotImager.Frame capture() throws Exception {
                sc.paste = frames.size() >= 42; // after the heights' 42 frames
                return super.capture();
            }
        };
        NeedleEndMeasure.Plan p = plan(sc, sc.endDx, sc.endDy);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, p);
        refused(r, NeedleEndMeasure.PASTE, "paste on the end: spread", cam);
        assertEquals(43, cam.frames.size());
        assertEquals(42.0, cam.frames.get(41)[2], 1e-9, "after the last height's frames");
        double[] last = cam.frames.get(42);
        assertEquals(p.needleX, last[0], 1e-9);
        assertEquals(p.needleY, last[1], 1e-9);
        assertEquals(40.4, last[2], 1e-9);
        int dwell = cam.lines.lastIndexOf("G4 P300");
        assertEquals(String.format(java.util.Locale.ROOT, "G1 X%.3f Y%.3f F3000", p.needleX - 0.5, p.needleY - 0.5),
                cam.lines.get(dwell - 3), "approached from (-0.5, -0.5)");
        assertEquals(java.util.Arrays.asList("G1 Z31.500 F1200", "M400"), cam.lines.subList(dwell + 1,
                cam.lines.size()), "then only the lift");
    }

    /**
     * Review MINOR 4: every frame is kept as taken (the camera's image, nothing drawn on it), in the order taken, for
     * Process's tools: the result and the record list them (N1's heights, the needle's, six each in the steps' order,
     * then the clean check's), each captioned with its tool, height and step; the clean verdict names its own. A
     * refusal keeps them too.
     */
    @Test
    public void everyFrameIsKeptAsTaken() throws Exception {
        FromBelowScene sc = new FromBelowScene(14);
        Camera cam = new Camera(sc);
        NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, plan(sc, sc.endDx, sc.endDy));
        assertTrue(r.ok, r.why);
        List<Long> ids = new ArrayList<>();
        for (long i = 1000; i < 1043; i++) {
            ids.add(i);
        }
        assertEquals(ids, r.framePhotoIds);
        assertEquals(ids, r.end.framePhotoIds);
        assertEquals(Long.valueOf(1042), r.end.clean.framePhotoId);
        assertTrue(cam.keptAsTaken.stream().allMatch(b -> b), "the camera's own images: " + cam.keptAsTaken);
        assertEquals("As taken: N1 at raw Z 19.000, B + (5.0, 0.0) mm (2 of 6)", cam.kept.get(1));
        assertEquals("As taken: the needle at raw Z 36.000, B + (0.0, -1.0) mm (5 of 6)", cam.kept.get(22));
        assertEquals("As taken: the clean check's frame, the needle at raw Z 40.400 over B", cam.kept.get(42));
        FromBelowScene dirty = new FromBelowScene(15);
        dirty.paste = true;
        Camera cam2 = new Camera(dirty);
        NeedleEndMeasure.Result p = NeedleEndMeasure.measure(cam2, plan(dirty, dirty.endDx, dirty.endDy));
        assertEquals(NeedleEndMeasure.PASTE, p.code, p.why);
        assertEquals(43, p.framePhotoIds.size());
        assertEquals(Long.valueOf(1042), p.clean.framePhotoId);
    }

    /**
     * Review NIT: a step moves a tip up to 18 px per mm at the nearest heights allowed, so a step the follow window
     * cannot reach (followPx for the needle, n1SearchPx for N1) is refused with the other settings.
     */
    @Test
    public void aStepTheFollowWindowCannotReachIsRefused() {
        DispenseConfig.NeedleEndSettings s = new DispenseConfig.NeedleEndSettings();
        List<String> p = new ArrayList<>();
        s.validate(p);
        assertEquals(new ArrayList<String>(), p, "the defaults: 1 mm under 60 px, 5 mm under 140");
        s.needleStepMm = 4; // 72 px
        s.validate(p);
        assertTrue(p.stream().anyMatch(x -> x.contains("needleStepMm x 18 must stay under followPx")), p.toString());
        p.clear();
        s.needleStepMm = 3; // 54 px
        s.validate(p);
        assertEquals(new ArrayList<String>(), p);
        s.n1SearchPx = 80; // N1's 5 mm: 90 px
        s.validate(p);
        assertTrue(p.stream().anyMatch(x -> x.contains("n1SearchPx")), p.toString());
    }

    /**
     * Re-review NIT 1: a fresh K by parallax stands at most maxKFromSwitchMm from its K_switch, so that limit is above
     * 0 and at most the window from K, 1.0 mm (a PUT of 100 would turn the check off).
     */
    @Test
    public void maxKFromSwitchIsAtMostTheWindowFromK() {
        DispenseConfig.NeedleEndSettings s = new DispenseConfig.NeedleEndSettings();
        for (double bad : new double[] {1.01, 100, 0, Double.NaN}) {
            s.maxKFromSwitchMm = bad;
            List<String> p = new ArrayList<>();
            s.validate(p);
            assertTrue(p.stream().anyMatch(x -> x.contains("maxKFromSwitchMm must be above 0 and at most 1.0 mm")),
                    bad + ": " + p);
        }
        s.maxKFromSwitchMm = HeightSwitch.K_WINDOW_MM;
        List<String> p = new ArrayList<>();
        s.validate(p);
        assertEquals(new ArrayList<String>(), p);
    }

    /**
     * Review MINOR 5: the clean check's found and z failing through the end measure are "the end was not found (light
     * or focus)", measure again, never paste: a peak under its limit, then a scale outside its range.
     */
    @Test
    public void foundAndZFailingAreTheEndNotFound() throws Exception {
        for (String check : new String[] {"found", "z"}) {
            FromBelowScene sc = new FromBelowScene(18);
            Camera cam = new Camera(sc);
            NeedleEndMeasure.Plan p = plan(sc, sc.endDx, sc.endDy);
            if ("found".equals(check)) {
                p.clean.peakMin = 1000;
            }
            else {
                p.clean.kLow = 17; // the needle at raw 40.4 is some 15.6 px/mm
                p.clean.kHigh = 18;
            }
            NeedleEndMeasure.Result r = NeedleEndMeasure.measure(cam, p);
            refused(r, NeedleEndMeasure.NOT_FOUND, "the end was not found (light or focus): " + check + ":", cam);
            assertEquals(check, r.clean.failed);
        }
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
