package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.driver.AbstractReferenceDriver.CommunicationsType;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;
import org.openpnp.spi.Camera;

/**
 * The SIM top camera on a simulated machine (GcodeDriver over loopback TCP to RecordingGcodeServer):
 * it draws the board only once the controller said GcodeServer, does not open on a serial machine or
 * without a scene, and pre-applies the configured flips so OpenPnP's transform shows the board the
 * right way round.
 */
public class SimBoardCameraTest {
    static final double UPP_X = 0.01068376, UPP_Y = 0.01072961;
    static final int W = 640, H = 480;
    /** Where the camera looks, relative to FID1's true position (mm). */
    static final double DX = 0.35, DY = -0.22;
    SimMachine sim;
    SimScene scene;
    final List<SimBoardCamera> cameras = new ArrayList<>();

    @BeforeAll
    public static void openCv() {
        nu.pattern.OpenCV.loadLocally();
    }

    @AfterEach
    public void after() throws Exception {
        for (SimBoardCamera cam : cameras) {
            cam.close();
        }
        if (sim != null) {
            sim.close();
        }
    }

    /** The scene file in the configuration copy; the board registered in the configuration (boards.xml). */
    void writeScene(File configDir) throws Exception {
        Board b = Configuration.get().getBoard(new File(configDir, "toy.board.xml"));
        Board ftp = SimBoards.ftpLike();
        b.setDimensions(ftp.getDimensions());
        for (Placement p : ftp.getPlacements()) {
            b.addPlacement(p);
        }
        String json = "{\"version\": 1, \"board\": {\"file\": \"toy.board.xml\","
                + " \"seat\": {\"x\": 296.2, \"y\": 114.1, \"rotationDeg\": -1.6, \"scaleX\": 1.002, \"scaleY\": 0.997,"
                + " \"shearDeg\": 0.1}}}";
        File f = SimGuard.sceneFile(configDir);
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
        scene = SimScene.load(f).withBoardSize(b);
    }

    SimBoardCamera camera(boolean flips) throws Exception {
        SimBoardCamera cam = new SimBoardCamera();
        cam.setName("Top");
        cam.setLooking(Camera.Looking.Down);
        cam.setUnitsPerPixel(new Location(LengthUnit.Millimeters, UPP_X, UPP_Y, 0, 0));
        cam.setViewWidth(W);
        cam.setViewHeight(H);
        cam.setFlipX(flips); // as the live Top camera (flip-x and flip-y)
        cam.setFlipY(flips);
        // No axes: the camera sits at its head offsets, here beside FID1.
        Point2D.Double fid1 = scene.toMachine(SimBoards.FTP_FIDUCIALS[0][0], SimBoards.FTP_FIDUCIALS[0][1]);
        cam.setHeadOffsets(new Location(LengthUnit.Millimeters, fid1.x + DX, fid1.y + DY, 0, 0));
        sim.machine.getDefaultHead().addCamera(cam);
        cameras.add(cam);
        return cam;
    }

    /** Where FID1 must appear in a frame the right way round. */
    static double[] expectedFid1() {
        return new double[] {W / 2.0 - DX / UPP_X, H / 2.0 + DY / UPP_Y};
    }

    static boolean uniform(BufferedImage img) {
        int ref = img.getRGB(W / 2, H / 2);
        for (int y = 20; y < H; y += 7) {
            for (int x = 0; x < W; x += 7) {
                if (img.getRGB(x, y) != ref) {
                    return false;
                }
            }
        }
        return true;
    }

    void identifyAs(String firmware) throws Exception {
        sim.server.firmwareName = firmware;
        SimGuard.noteIdentity(sim.machine, new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
    }

    @Test
    public void drawsTheBoardTheRightWayRoundOnceTheControllerSaidGcodeServer() throws Exception {
        sim = new SimMachine();
        writeScene(sim.configDir);
        SimBoardCamera cam = camera(true);
        identifyAs("GcodeServer (RecordingGcodeServer)");
        assertTrue(SimGuard.isSimulated(sim.machine));
        BufferedImage seen = cam.captureTransformed(); // ReferenceCamera flips it back (OpenCV)
        assertNotNull(seen);
        assertEquals(W, seen.getWidth());
        double[] e = expectedFid1();
        double[] c = SimBoardRendererTest.centroid(seen, e[0], e[1], 0.75 / UPP_X);
        assertEquals(e[0], c[0], 0.1, "x: FID1 left of the centre, as the camera sits right of it");
        assertEquals(e[1], c[1], 0.1, "y: FID1 below the centre (+y up)");
        // The raw frame is the drawing turned by 180° (pre-flipped), so the flips undo it.
        BufferedImage raw = cam.internalCapture();
        double[] r = SimBoardRendererTest.centroid(raw, W - 1 - e[0], H - 1 - e[1], 0.75 / UPP_X);
        assertEquals(W - 1 - e[0], r[0], 0.1);
        assertEquals(H - 1 - e[1], r[1], 0.1);
        assertTrue(cam.getSourceUri().endsWith("sim-1px.png"), "never the 7002 x 4000 test image");
    }

    @Test
    public void loopbackButUnconfirmedGivesABlankFrame() throws Exception {
        sim = new SimMachine();
        writeScene(sim.configDir);
        SimBoardCamera cam = camera(false);
        assertTrue(uniform(cam.internalCapture()), "no M115 yet");
        identifyAs("Marlin (RecordingGcodeServer)");
        assertTrue(uniform(cam.internalCapture()), "a Marlin behind a loopback port");
        identifyAs("GcodeServer (RecordingGcodeServer)");
        assertFalse(uniform(cam.internalCapture()));
        sim.machine.setEnabled(false);
        sim.machine.setEnabled(true);
        assertTrue(uniform(cam.internalCapture()), "a reconnect needs a new M115");
    }

    @Test
    public void serialMachineRefusesToOpen() throws Exception {
        sim = new SimMachine(false);
        writeScene(sim.configDir);
        sim.driver.setCommunicationsType(CommunicationsType.serial);
        SimGuard.noteIdentity(sim.machine, SimGuardTest.GCODE_SERVER);
        SimBoardCamera cam = camera(false);
        IllegalStateException e = assertThrows(IllegalStateException.class, cam::open);
        assertEquals("simulation-only camera on a non-simulated machine: driver GcodeDriver uses serial", e.getMessage());
        assertNull(cam.internalCapture(), "no frame at all");
        assertFalse(cam.getSourceUri().endsWith("sim-1px.png"), "nothing was changed");
        sim.driver.setCommunicationsType(CommunicationsType.tcp);
        sim.driver.getTcp().setIpAddress("192.168.1.20");
        e = assertThrows(IllegalStateException.class, cam::open);
        assertTrue(e.getMessage().endsWith("uses tcp to 192.168.1.20"), e.getMessage());
    }

    @Test
    public void noSceneRefusesToOpen() throws Exception {
        sim = new SimMachine();
        writeScene(sim.configDir);
        SimBoardCamera cam = camera(false);
        assertTrue(SimGuard.sceneFile(Configuration.get()).delete());
        IllegalStateException e = assertThrows(IllegalStateException.class, cam::open);
        assertTrue(e.getMessage().startsWith("simulation-only camera without a sim scene"), e.getMessage());
    }

    @Test
    public void onlyFlipsAreSupported() throws Exception {
        sim = new SimMachine();
        writeScene(sim.configDir);
        SimBoardCamera cam = camera(true);
        identifyAs("GcodeServer (RecordingGcodeServer)");
        assertNotNull(cam.internalCapture());
        cam.setRotation(90);
        IllegalStateException e = assertThrows(IllegalStateException.class, cam::internalCapture);
        assertTrue(e.getMessage().startsWith("SimBoardCamera supports flips only"), e.getMessage());
        cam.setRotation(0);
        cam.setCropWidth(320);
        assertThrows(IllegalStateException.class, cam::internalCapture);
    }
}
