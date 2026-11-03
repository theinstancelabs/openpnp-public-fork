package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.File;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Configuration;
import org.openpnp.util.Utils2D;
import org.openpnp.util.Utils2D.AffineInfo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** The scene file format (spec §9.2), its seat conventions, and fiducials taken from a board. */
public class SimSceneTest {
    /** The example of the spec (§9.2), fiducials as il_sim_scene.py writes them from ftp.board.xml. */
    static final String EXAMPLE = "{\"version\": 1,"
            + " \"board\": {\"file\": \"ftp.board.xml\", \"size\": [75.0, 75.0],"
            + "  \"seat\": {\"affine\": [[1.00298, 0.03343, 295.99], [-0.02507, 0.99714, 114.52]]},"
            + "  \"surface\": {\"z0\": 5.43, \"slopeX\": -0.0006, \"slopeY\": 0.0009},"
            + "  \"fiducials\": [{\"name\": \"FID1\", \"bx\": 62.4466, \"by\": 4.6910, \"d\": 1.0, \"mask\": 3.0},"
            + "   {\"name\": \"FID2\", \"bx\": 12.5, \"by\": 70.0, \"d\": 1.0, \"mask\": 3.0}],"
            + "  \"hideFiducials\": [\"FID2\"], \"dirtyPads\": [\"R12.1\"], \"dirtySpots\": [{\"bx\": 1, \"by\": 2}],"
            + "  \"leakySpots\": [{\"bx\": 8.0, \"by\": 8.0, \"r\": 0.6}]},"
            + " \"blank\": {\"rect\": [280, 225, 365, 250], \"surface\": {\"z0\": 5.62, \"slopeX\": -0.0012, \"slopeY\": 0.0},"
            + "  \"dots\": [{\"x\": 300, \"y\": 230}]},"
            + " \"bedZ\": 2.5, \"n1OffsetMm\": [-24.178, -63.91], \"tipRadiusMm\": 0.35,"
            + " \"vacuum\": {\"openAir\": -1550000, \"sealDrops\": [257000, 404000], \"partialBandMm\": 0.03,"
            + "  \"leakDrop\": 15000, \"offValue\": 0, \"tauS\": 0.3, \"sealTauS\": 0.03, \"releaseTauS\": 0.4,"
            + "  \"noise\": 1500, \"seed\": 7, \"tip\": \"part\"},"
            + " \"camera\": {\"uppError\": [0.0, 0.03], \"noise\": 4, \"blurPx\": 1}}";

    @BeforeAll
    public static void configuration() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("sim-scene").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
    }

    static JsonObject json(String s) {
        return new JsonParser().parse(s).getAsJsonObject();
    }

    @Test
    public void parsesTheSpecExample() {
        SimScene s = SimScene.of(json(EXAMPLE));
        assertEquals("ftp.board.xml", s.boardFile);
        assertEquals(75, s.boardWidthMm, 0);
        Point2D.Double origin = s.toMachine(0, 0);
        assertEquals(295.99, origin.x, 1e-12, "the board origin lands at the translation");
        assertEquals(114.52, origin.y, 1e-12);
        Point2D.Double p = s.toMachine(10, 20);
        assertEquals(295.99 + 1.00298 * 10 + 0.03343 * 20, p.x, 1e-12);
        assertEquals(114.52 - 0.02507 * 10 + 0.99714 * 20, p.y, 1e-12);
        Point2D.Double back = s.toBoard(p.x, p.y);
        assertEquals(10, back.x, 1e-9);
        assertEquals(20, back.y, 1e-9);
        assertEquals(2, s.fiducials.size());
        assertEquals(62.4466, s.fiducials.get(0).bx, 0);
        assertTrue(s.hideFiducials.contains("FID2"));
        assertEquals(1, s.visibleFiducials(null).size());
        assertTrue(s.dirtyPads.contains("R12.1"));
        assertEquals(2, s.dirtySpots.get(0)[1], 0);
        assertEquals(0.6, s.leakySpots.get(0).r, 0);
        assertEquals(5.62, s.blankContactZ(280, 225), 1e-12);
        assertEquals(5.62 - 0.0012 * 85, s.blankContactZ(365, 240), 1e-12);
        assertEquals(5.43 - 0.0006 * 75, s.boardContactZ(75, 0), 1e-12);
        assertEquals(300, s.blankDots.get(0)[0], 0);
        assertEquals(-63.91, s.n1OffsetY, 0);
        assertEquals("part", s.vacuum.tip);
        assertEquals(2, s.vacuum.sealDrops.length);
        assertEquals(0.03, s.camera.uppError[1], 0);
        assertEquals(SimScene.Region.BOARD, s.regionAt(p.x, p.y));
        assertEquals(SimScene.Region.BLANK, s.regionAt(300, 230));
        assertEquals(SimScene.Region.BED, s.regionAt(100, 100));
    }

    @Test
    public void defaultsAreTheBench() {
        SimScene s = SimScene.of(json("{\"board\": {\"seat\": {\"x\": 10, \"y\": 20}}}"));
        assertEquals(-1_550_000, s.vacuum.openAir, 0);
        assertEquals(9, s.vacuum.sealDrops.length);
        assertEquals(0.3, s.vacuum.tauS, 0);
        assertEquals(0.4, s.vacuum.releaseTauS, 0);
        assertEquals("normal", s.vacuum.tip);
        assertEquals(2.5, s.bedZ, 0);
        assertEquals(-24.178, s.n1OffsetX, 0);
        assertEquals(0.35, s.tipRadiusMm, 0);
        assertEquals(5.43, s.boardSurface.z0, 0);
        assertNull(s.fiducials, "no fiducials in the scene: the renderer takes the board's");
        assertEquals(0, s.boardWidthMm, 0, "no size: taken from the board");
    }

    @Test
    public void refusesWhatTheModelsCannotUse() {
        assertThrows(IllegalArgumentException.class, () -> SimScene.of(json("{\"board\": {}}")));
        assertThrows(IllegalArgumentException.class, () -> SimScene.of(json("{}")));
        assertThrows(IllegalArgumentException.class, () -> SimScene.of(json("{\"version\": 2, \"board\": {\"seat\": {}}}")));
        assertThrows(IllegalArgumentException.class,
                () -> SimScene.of(json("{\"board\": {\"seat\": {\"scaleX\": 0}}}")));
        assertThrows(IllegalArgumentException.class,
                () -> SimScene.of(json("{\"board\": {\"seat\": {}}, \"vacuum\": {\"tip\": \"bent\"}}")));
        assertThrows(IllegalArgumentException.class,
                () -> SimScene.of(json("{\"board\": {\"seat\": {}}, \"vacuum\": {\"tauS\": -1}}")));
        assertThrows(IllegalArgumentException.class,
                () -> SimScene.of(json("{\"board\": {\"seat\": {}}, \"vacuum\": {\"sealDrops\": []}}")));
    }

    /** The parametric seat is T·R·Shear·Scale, the decomposition Utils2D.affineInfo (the engine's fit report) uses. */
    @Test
    public void parametricSeatMatchesTheEngineDecomposition() {
        SimScene s = SimScene.of(json("{\"board\": {\"seat\": {\"x\": 296.5, \"y\": 114.1, \"rotationDeg\": -1.6,"
                + " \"scaleX\": 1.002, \"scaleY\": 0.997, \"shearDeg\": 0.1}}}"));
        AffineInfo info = Utils2D.affineInfo(s.seat);
        assertEquals(-1.6, info.rotationAngleDeg, 1e-9);
        assertEquals(1.002, info.xScale, 1e-12);
        assertEquals(0.997, info.yScale, 1e-12);
        assertEquals(0.1, Math.toDegrees(Math.atan(info.xShear)), 1e-9);
        assertEquals(296.5, info.xTranslation, 1e-12);
        assertEquals(114.1, info.yTranslation, 1e-12);
        AffineTransform same = SimScene.seat(296.5, 114.1, -1.6, 1.002, 0.997, 0.1);
        assertEquals(same, s.seat);
    }

    @Test
    public void fiducialsComeFromTheBoard() {
        List<SimScene.Fiducial> ftp = SimScene.fiducialsOf(SimBoards.ftpLike());
        assertEquals(3, ftp.size());
        assertEquals("FID1", ftp.get(0).name);
        assertEquals(62.4465812, ftp.get(0).bx, 1e-9);
        assertEquals(1.0, ftp.get(0).d, 0, "parsed from Fiducial_1mm_Mask3mm");
        assertEquals(3.0, ftp.get(0).mask, 0);
        List<SimScene.Fiducial> kicad = SimScene.fiducialsOf(SimBoards.kicadLike());
        assertEquals(2, kicad.size(), "the disabled one is left out");
        assertEquals(1.2, kicad.get(0).d, 1e-12, "the round footprint pad wins");
        assertEquals(3.0, kicad.get(0).mask, 0, "no mask in the name: 3.0");
        // A scene without fiducials shows the board's.
        SimScene s = SimScene.of(json("{\"board\": {\"seat\": {}}}"));
        assertEquals(2, s.visibleFiducials(SimBoards.kicadLike()).size());
        s.hideFiducials.add("FID_A");
        assertEquals("FID_B", s.visibleFiducials(SimBoards.kicadLike()).get(0).name);
        assertSame(s, s.withBoardSize(SimBoards.kicadLike()));
        assertEquals(40, s.boardWidthMm, 0);
        assertEquals(30, s.boardHeightMm, 0);
    }
}
