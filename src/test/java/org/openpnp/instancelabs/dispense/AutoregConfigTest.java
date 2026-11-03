package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/** The registration, probe and homing settings: defaults, ranges, old files, copies. */
public class AutoregConfigTest {
    static void refused(String key, Consumer<DispenseConfig> change) {
        DispenseConfig c = new DispenseConfig();
        change.accept(c);
        List<String> p = c.validate();
        assertTrue(p.stream().anyMatch(x -> x.contains(key)), key + ": " + p);
    }

    static void accepted(Consumer<DispenseConfig> change) {
        DispenseConfig c = new DispenseConfig();
        change.accept(c);
        assertEquals(new ArrayList<>(), c.validate());
    }

    @Test
    public void defaults() {
        DispenseConfig c = new DispenseConfig();
        assertEquals(new ArrayList<>(), c.validate());
        assertEquals("whenNeeded", c.homeBeforeRun);
        assertEquals(2, c.fiducialCheck.minFiducials);
        assertEquals(8.0, c.fiducialCheck.searchRadiusMm, 0);
        assertEquals(0.10, c.fiducialCheck.seatToleranceMm, 0);
        assertEquals(2.5, c.vacuumProbe.startAboveMm, 0);
        assertEquals(1.5, c.vacuumProbe.floorBelowMm, 0);
        assertEquals(-1900000, c.vacuumProbe.openAirMinCounts);
        assertEquals(-1200000, c.vacuumProbe.openAirMaxCounts);
        assertEquals(4, c.vacuumProbe.alternates);
        assertEquals(1.4, c.vacuumProbe.padKeepOutMm, 0);
        assertEquals(1500, c.vacuumProbe.vacuumSettleMs);
        assertEquals(70, c.vacuumProbe.readDelayMs);
        assertTrue(c.vacuumProbe.boardSpots.isEmpty());
        assertTrue(c.boardRegistrations.isEmpty());
        assertNull(c.newSeatAtMs);
        VacuumProber.Settings s = VacuumProber.Settings.of(c, false);
        assertEquals(1500, s.vacuumSettleMs);
        assertEquals(c.commandTimeoutMs, s.commandTimeoutMs);
        assertNull(VacuumProber.timingProblem(s));
    }

    @Test
    public void ranges() {
        refused("homeBeforeRun", c -> c.homeBeforeRun = "never");
        accepted(c -> c.homeBeforeRun = "always");
        refused("minFiducials", c -> c.fiducialCheck.minFiducials = 1);
        refused("minFiducials", c -> c.fiducialCheck.minFiducials = 11);
        accepted(c -> c.fiducialCheck.minFiducials = 3);
        refused("searchRadiusMm", c -> c.fiducialCheck.searchRadiusMm = 0.5);
        refused("searchRadiusMm", c -> c.fiducialCheck.searchRadiusMm = 12.5);
        accepted(c -> c.fiducialCheck.searchRadiusMm = 12);
        refused("seatToleranceMm", c -> c.fiducialCheck.seatToleranceMm = 0.005);
        refused("seatToleranceMm", c -> c.fiducialCheck.seatToleranceMm = 1.5);
        refused("startAboveMm", c -> c.vacuumProbe.startAboveMm = 0.4);
        refused("startAboveMm", c -> c.vacuumProbe.startAboveMm = 10.5);
        refused("floorBelowMm", c -> c.vacuumProbe.floorBelowMm = 0.1);
        refused("floorBelowMm", c -> c.vacuumProbe.floorBelowMm = 3.5);
        refused("openAirMinCounts", c -> c.vacuumProbe.openAirMinCounts = -1000000);
        refused("openAirMinCounts", c -> c.vacuumProbe.openAirMaxCounts = 10);
        refused("alternates", c -> c.vacuumProbe.alternates = -1);
        refused("alternates", c -> c.vacuumProbe.alternates = 5);
        accepted(c -> c.vacuumProbe.alternates = 0);
        refused("padKeepOutMm", c -> c.vacuumProbe.padKeepOutMm = 0.4);
        refused("padKeepOutMm", c -> c.vacuumProbe.padKeepOutMm = 5.5);
        refused("vacuumSettleMs", c -> c.vacuumProbe.vacuumSettleMs = -1);
        refused("vacuumSettleMs", c -> c.vacuumProbe.vacuumSettleMs = 5001);
        accepted(c -> c.vacuumProbe.vacuumSettleMs = 300); // a simulator value: refused at task start, not here
        refused("readDelayMs", c -> c.vacuumProbe.readDelayMs = 501);
        accepted(c -> c.vacuumProbe.readDelayMs = 0);
    }

    @Test
    public void searchRadiusNeedsAtMostNineFramesAtTheCameraSize() {
        double w = DispenseConfig.FiducialCheck.DEFAULT_FRAME_W_MM, h = DispenseConfig.FiducialCheck.DEFAULT_FRAME_H_MM;
        assertEquals(3, DispenseConfig.FiducialCheck.framesNeeded(8.0, w, h, 1.0), "the prediction, then (0, +/-9.2)");
        assertEquals(1, DispenseConfig.FiducialCheck.framesNeeded(4.5, w, h, 1.0));
        assertEquals(9, DispenseConfig.FiducialCheck.framesNeeded(12.0, w, h, 1.0));
        DispenseConfig.FiducialCheck f = new DispenseConfig.FiducialCheck();
        assertNull(f.searchProblem(w, h, 1.0));
        // a 640 x 480 frame at the same scale needs more frames than allowed for 8 mm
        String why = f.searchProblem(640 * 0.0107, 480 * 0.0107, 1.0);
        assertNotNull(why);
        assertTrue(why.startsWith("fiducialCheck.searchRadiusMm 8.0 needs"), why);
        f.searchRadiusMm = 3;
        assertNull(f.searchProblem(640 * 0.0107, 480 * 0.0107, 1.0));
        assertEquals(Integer.MAX_VALUE, DispenseConfig.FiducialCheck.framesNeeded(8, 2, 2, 1.0), "no usable area");
    }

    @Test
    public void boardSpotsShapes() {
        accepted(c -> c.vacuumProbe.boardSpots.put("ftp.board.xml", Arrays.asList(new double[] {8, 8},
                new double[] {67, 8}, new double[] {67, 67}, new double[] {8, 67}, new double[] {37, 37})));
        refused("boardSpots", c -> c.vacuumProbe.boardSpots.put("a", Arrays.asList(new double[] {8, 8},
                new double[] {67, 8})));
        refused("boardSpots", c -> c.vacuumProbe.boardSpots.put("a", Arrays.asList(new double[] {8, 8},
                new double[] {67, 8}, new double[] {67})));
        refused("boardSpots", c -> c.vacuumProbe.boardSpots.put("a", Arrays.asList(new double[] {8, 8},
                new double[] {67, 8}, new double[] {67, Double.NaN})));
        List<double[]> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new double[] {i * 5, 5});
        }
        refused("boardSpots", c -> c.vacuumProbe.boardSpots.put("a", ten));
    }

    static BoardRegistration reg(String key, long atMs) {
        BoardRegistration r = new BoardRegistration();
        r.id = "id-" + key;
        r.boardKey = key;
        r.atMs = atMs;
        r.affine = new double[] {1.00112, -0.0057, 0.00103, 0.9946, 295.99, 114.52};
        return r;
    }

    @Test
    public void registrationsAreCappedAndChecked() {
        DispenseConfig c = new DispenseConfig();
        for (int i = 0; i < 25; i++) {
            c.putBoardRegistration(reg("file:/b" + i, 1000 + i));
        }
        assertEquals(DispenseConfig.MAX_BOARD_REGISTRATIONS, c.boardRegistrations.size());
        assertFalse(c.boardRegistrations.containsKey("file:/b0"), "the oldest went");
        assertTrue(c.boardRegistrations.containsKey("file:/b24"));
        assertEquals("file:/b7", c.boardRegistrationById("id-file:/b7").boardKey);
        assertNull(c.boardRegistrationById("nope"));
        // a re-registration replaces its key's record
        c.putBoardRegistration(reg("file:/b24", 5000));
        assertEquals(5000, c.boardRegistrations.get("file:/b24").atMs);
        assertEquals(new ArrayList<>(), c.validate());
        refused("boardRegistrations", x -> x.boardRegistrations.put("file:/x", reg("file:/y", 1)));
        refused("boardRegistrations", x -> {
            BoardRegistration r = reg("file:/x", 1);
            r.affine = new double[] {1, 0, 0, 0, 3, 4};
            x.boardRegistrations.put("file:/x", r);
        });
        refused("boardRegistrations", x -> {
            BoardRegistration r = reg("file:/x", 1);
            r.affine = new double[] {1, 0, 0, 1, Double.POSITIVE_INFINITY, 4};
            x.boardRegistrations.put("file:/x", r);
        });
    }

    @Test
    public void anOldFileLoadsWithTheDefaultsAndCopiesRoundTrip() {
        String old = "{\"schemaVersion\": 3, \"nozzle\": \"N2\", \"hopHeightMm\": 1.0}";
        DispenseConfig c = DispenseConfig.fromJson(old);
        assertEquals("whenNeeded", c.homeBeforeRun);
        assertNotNull(c.fiducialCheck);
        assertEquals(8.0, c.fiducialCheck.searchRadiusMm, 0);
        assertNotNull(c.vacuumProbe);
        assertNotNull(c.vacuumProbe.boardSpots);
        assertNotNull(c.boardRegistrations);
        assertNull(c.newSeatAtMs);
        assertEquals(new ArrayList<>(), c.validate());
        // explicit nulls (a hand-edited file) get the defaults too
        c = DispenseConfig.fromJson("{\"homeBeforeRun\": null, \"fiducialCheck\": null, \"vacuumProbe\": "
                + "{\"alternates\": 1, \"boardSpots\": null}, \"boardRegistrations\": null}");
        assertEquals("whenNeeded", c.homeBeforeRun);
        assertEquals(2, c.fiducialCheck.minFiducials);
        assertEquals(1, c.vacuumProbe.alternates);
        assertEquals(2.5, c.vacuumProbe.startAboveMm, 0, "a partial object keeps the other defaults");
        assertNotNull(c.vacuumProbe.boardSpots);
        // copy() round trip with every new field set
        DispenseConfig a = new DispenseConfig();
        a.homeBeforeRun = "always";
        a.fiducialCheck.minFiducials = 3;
        a.vacuumProbe.padKeepOutMm = 2.0;
        a.vacuumProbe.boardSpots.put("ftp.board.xml", Arrays.asList(new double[] {8, 8}, new double[] {67, 8},
                new double[] {37, 37}));
        a.putBoardRegistration(reg("file:/ftp.board.xml", 123));
        a.newSeatAtMs = 456L;
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.simulated = true;
        a.surfaces.put("board", m);
        DispenseConfig b = a.copy();
        assertEquals(a.toJson(), b.toJson());
        assertEquals("always", b.homeBeforeRun);
        assertEquals(3, b.fiducialCheck.minFiducials);
        assertEquals(37, b.vacuumProbe.boardSpots.get("ftp.board.xml").get(2)[1], 0);
        assertEquals(295.99, b.boardRegistrations.get("file:/ftp.board.xml").affine[4], 0);
        assertEquals(Long.valueOf(456), b.newSeatAtMs);
        assertTrue(b.surfaces.get("board").simulated);
    }
}
