package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.MotionPlanner.CompletionType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tip setups (§22.8), what the SIM fakes: the scene's {@code paste.byTip} and {@code physicalTip} (as
 * il_sim_scene.py --paste-by-tip and --physical-tip write them), the paste of the tip on the head (its own mass per
 * turn and landing bias), and the SIM bottom camera drawing that tip end-on only while N2 is over it.
 */
public class SimTipTest {
    static final String K25 = "25g-straight";
    /** What {@code il_sim_scene.py amend --paste --paste-by-tip 25g-straight:mRevMg=1.6,c=0.6,landing=2.4:-0.4
     * --physical-tip 25g-straight} writes (the paste block shortened to the fields it needs). */
    static final String SCENE = "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, "
            + "\"y\": 0}}, \"paste\": {\"mRevMg\": 2.3, \"footprint\": {\"c\": 0.875, \"e\": 0.77}, \"byTip\": "
            + "{\"25g-straight\": {\"mRevMg\": 1.6, \"footprint\": {\"c\": 0.6}, \"landingBiasMm\": [2.4, -0.4]}}}, "
            + "\"physicalTip\": \"25g-straight\"}";
    static final int CX = SimTipCamera.WIDTH_PX / 2, CY = SimTipCamera.HEIGHT_PX / 2;
    static final int BG = new Color(SimTipCamera.BACKGROUND, SimTipCamera.BACKGROUND, SimTipCamera.BACKGROUND).getRGB();

    SimMachine sim;
    DispenseService svc;

    @AfterEach
    public void after() throws Exception {
        if (svc != null) {
            svc.shutdown();
        }
        if (sim != null) {
            sim.close();
        }
    }

    static JsonObject json(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    @Test
    public void theSceneGivesATipItsOwnPasteAndNamesTheTipOnTheHead() {
        SimScene s = SimScene.of(json(SCENE));
        assertEquals(K25, s.physicalTip);
        SimScene.TipPaste t = s.paste.byTip.get(K25);
        assertEquals(1.6, t.mRevMg, 0);
        assertEquals(0.6, t.footprintC, 0);
        assertNull(t.footprintE, "the paste's");
        assertArrayEquals(new double[] {2.4, -0.4}, t.landingBiasMm, 0);
        SimScene.Paste p = s.pasteFor(K25);
        assertEquals(1.6, p.mRevMg, 0);
        assertEquals(0.6, p.footprintC, 0);
        assertEquals(0.77, p.footprintE, 0);
        assertArrayEquals(new double[] {2.4, -0.4}, p.landingBiasMm, 0);
        assertEquals(s.paste.cv, p.cv, 0);
        assertArrayEquals(s.paste.n2OffsetMm, p.n2OffsetMm, 0);
        assertSame(p, s.pasteFor(K25), "made once per scene");
        assertSame(s.paste, s.pasteFor(Tip.SEED_KEY), "a tip without an entry: the paste itself");
        assertSame(s.paste, s.pasteFor(null));
        assertEquals(2.3, s.paste.mRevMg, 0, "the scene's paste is untouched");
        assertArrayEquals(new double[] {0, 0}, s.paste.landingBiasMm, 0);
        assertNull(SimScene.of(json("{\"board\": {\"file\": \"t\", \"seat\": {\"x\": 0, \"y\": 0}}}")).physicalTip);
        String board = "{\"board\": {\"file\": \"t\", \"seat\": {\"x\": 0, \"y\": 0}}, \"paste\": {\"byTip\": {\"k\": ";
        for (String bad : new String[] {"{\"mRevMg\": 0}}}}", "{\"footprint\": {\"c\": -1}}}}}",
            "{\"landingBiasMm\": [1]}}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> SimScene.of(json(board + bad)), bad);
        }
    }

    /** The bare law of SimPasteTest with the scene's per-tip block; ripple off so a half turn lays mRev / 2. */
    static SimScene exact(String physicalTip) {
        SimScene s = SimPasteTest.exact();
        s.paste.ripple = 0;
        SimScene.TipPaste t = new SimScene.TipPaste();
        t.mRevMg = 1.6;
        t.landingBiasMm = new double[] {2.4, -0.4};
        s.paste.byTip.put(K25, t);
        s.physicalTip = physicalTip;
        return s;
    }

    static SimPaste.Deposit halfTurn(SimScene s) {
        long[] now = {0};
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> now[0], () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.dot(300, 240, SimPasteTest.BLANK_Z, 800);
        return p.deposits("blank").get(0);
    }

    @Test
    public void theTipOnTheHeadLaysItsOwnPasteWhereItLands() {
        SimPaste.Deposit d22 = halfTurn(exact(Tip.SEED_KEY));
        assertEquals(2.30 / 2, d22.mg, 1e-9, "the paste's 2.30 mg per revolution");
        assertEquals(300, d22.x, 1e-9);
        assertEquals(240, d22.y, 1e-9);
        SimPaste.Deposit d25 = halfTurn(exact(K25));
        assertEquals(1.6 / 2, d25.mg, 1e-9, "the 25G's 1.6 mg per revolution");
        assertEquals(302.4, d25.x, 1e-9, "2.4 mm off in X");
        assertEquals(239.6, d25.y, 1e-9);
        assertTrue(d25.areaMm2() < d22.areaMm2(), "smaller dots");
    }

    static TipSetup straight25() {
        TipSetup s = new TipSetup();
        s.key = K25;
        s.name = "25G straight";
        s.dispenser = TipSetup.DEFAULT_DISPENSER;
        s.tipModel = "DSKIT1 25G";
        s.gauge = 25;
        s.idMm = 0.25;
        s.odMm = 0.51;
        s.odSource = TipSetup.OD_ASSUMED;
        s.form = TipSetup.FORM_STRAIGHT;
        s.taper = TipSetup.TAPER_STEEL;
        s.lengthMm = 12.7;
        s.minDotSteps = 400;
        return s;
    }

    static boolean same(BufferedImage a, BufferedImage b) {
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Pixels of the frame below the tag's band that are not the background. */
    static int drawn(BufferedImage img) {
        int n = 0;
        for (int y = 40; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                n += img.getRGB(x, y) != BG ? 1 : 0;
            }
        }
        return n;
    }

    @Test
    public void theBottomCameraDrawsATipEndOn() {
        BufferedImage empty = SimTipCamera.render(null, 0, 0, 0);
        assertEquals(1280, empty.getWidth());
        assertEquals(960, empty.getHeight());
        assertEquals(0, drawn(empty));
        int tag = 0;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 300; x++) {
                tag += empty.getRGB(x, y) != BG ? 1 : 0;
            }
        }
        assertTrue(tag > 50, "the tag " + SimTipCamera.TAG);
        // the seeded 22G: bent, tapered plastic, ID 0.41 (20 px) and OD 0.72 (36 px)
        BufferedImage g22 = SimTipCamera.render(TipSetup.seed(), 0, 0, 0);
        int grey = SimTipCamera.GREY.getRGB(), amber = SimTipCamera.AMBER.getRGB();
        assertEquals(Color.BLACK.getRGB(), g22.getRGB(CX, CY), "the bore");
        assertEquals(grey, g22.getRGB(CX + 14, CY), "the tube between ID and OD");
        assertEquals(amber, g22.getRGB(CX, CY - 40), "the 2.5 mm amber ring of a tapered plastic tip");
        assertEquals(grey, g22.getRGB(CX, CY + 200), "the bend toward the hub (-y when not recorded), 6 mm");
        assertEquals(BG, g22.getRGB(CX, CY - 200));
        // the 25G straight steel: no ring, no bend, a smaller disc
        BufferedImage s25 = SimTipCamera.render(straight25(), 0, 0, 0);
        assertEquals(Color.BLACK.getRGB(), s25.getRGB(CX, CY));
        assertEquals(grey, s25.getRGB(CX + 9, CY));
        assertEquals(BG, s25.getRGB(CX + 14, CY), "OD 0.51: 12.75 px");
        assertEquals(BG, s25.getRGB(CX, CY - 40));
        assertEquals(BG, s25.getRGB(CX, CY + 200));
        assertFalse(same(g22, s25), "two setups, two pictures");
        // a recorded bend: 12 mm at 30 deg pointing +x, so 6 mm of tube toward -x
        TipSetup bent = TipSetup.seed();
        bent.bendToward = "+x";
        bent.lengthMm = 12.0;
        assertEquals(grey, SimTipCamera.render(bent, 0, 0, 0).getRGB(CX - 250, CY));
        assertEquals(BG, SimTipCamera.render(bent, 0, 0, 0).getRGB(CX + 250, CY));
        // where N2 is: 2 mm right and up is 100 px right and up; outside the frame nothing is drawn
        assertEquals(Color.BLACK.getRGB(), SimTipCamera.render(straight25(), 2, 2, 0).getRGB(CX + 100, CY - 100));
        assertTrue(same(empty, SimTipCamera.render(straight25(), 13, 0, 0)));
        assertTrue(same(empty, SimTipCamera.render(straight25(), 0, -9.7, 0)));
        // its height above the focal plane blurs it: the tube's edge spreads outward
        BufferedImage soft = SimTipCamera.render(straight25(), 0, 0, 10);
        assertEquals(BG, s25.getRGB(CX + 16, CY));
        assertTrue((soft.getRGB(CX + 16, CY) & 0xff) > SimTipCamera.BACKGROUND + 5, "soft at 10 mm");
        assertEquals(s25.getWidth(), soft.getWidth());
    }

    void writeScene(String physicalTip) throws Exception {
        String json = "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\", \"seat\": {\"x\": 0, \"y\": 0}}"
                + (physicalTip == null ? "" : ", \"physicalTip\": \"" + physicalTip + "\"") + "}";
        File f = SimGuard.sceneFile(sim.configDir);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8)); // another size: the camera reads it again
    }

    void moveN2(double x, double y, double z) throws Exception {
        sim.n2.moveTo(new Location(LengthUnit.Millimeters, x, y, z, Double.NaN));
        sim.machine.getMotionPlanner().waitForCompletion(null, CompletionType.WaitForStillstand);
    }

    @Test
    public void theBottomCameraSeesTheTipOnTheHeadOnlyWhileN2IsOverIt() throws Exception {
        sim = new SimMachine(false);
        writeScene(K25);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.noteIdentity(sim.machine, new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
        svc.getDb().insertTipSetupVersion(straight25());
        SimTipCamera cam = new SimTipCamera();
        cam.setName("Bottom");
        cam.setLooking(Camera.Looking.Up);
        cam.setHeadOffsets(new Location(LengthUnit.Millimeters, 217.13, 152.32, 20, 0));
        sim.machine.addCamera(cam);
        assertEquals(Tip.SEED_KEY, svc.mountedKey());
        // N2 elsewhere: the empty frame
        moveN2(150, 100, 31.5);
        assertEquals(0, drawn(cam.internalCapture()));
        // N2 over the camera: the tip the scene says is on the head (the 25G, while the 22G is mounted)
        moveN2(217.13, 152.32, 31.5);
        BufferedImage seen = cam.internalCapture();
        assertTrue(drawn(seen) > 100);
        assertEquals(BG, seen.getRGB(CX, CY - 40), "no amber ring: the 25G straight steel");
        // without physicalTip: the mounted 22G
        writeScene(null);
        Thread.sleep(SimScene.Source.CHECK_MS + 50);
        seen = cam.internalCapture();
        assertTrue(drawn(seen) > 100);
        assertTrue(((seen.getRGB(CX, CY - 40) >> 16) & 0xff) > 150, "the 22G's amber ring (blurred at 11.5 mm)");
    }
}
