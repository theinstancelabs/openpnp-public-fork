package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The I4 Z frame (motion review findings): needle frame anchored by an N1 and a needle contact,
 * places and surfaces checked against it, per-dot Z from the probed board plane, a hard needle
 * floor, travel / hop bounds on the see-saw. Numbers from the bench: the ftp board of the p18_board1
 * run probed with N1 after its re-seat (runs/zprobe_r7.json: N1 contacts 5.35..5.55, plane
 * residual 0.035 mm), in machine coordinates through that run's board registration; the needle
 * touches at raw Z = 65.59 - N1 contact Z (side camera, 2026-10-27), i.e. 2.59 mm below N1 in
 * OpenPnP's N2 frame (z2 = 63 - raw), and dispenses at raw 65.49 - N1 contact Z at the 0.10 mm gap
 * (p18_board1: raw 59.93..60.13). ToyMapper: raw Z = 63 - N2 Z.
 */
public class ZFrameTest {
    /** zprobe_r7.json points (machine x, y, N1 contact z): four corners 8 mm in from the edges and the centre. */
    static final double[][] ZMAP = {{303.848, 122.843, 5.425}, {362.828, 122.012, 5.35}, {363.397, 180.645, 5.45},
        {304.417, 181.476, 5.55}, {333.118, 151.254, 5.4}};

    static List<DispenseConfig.SurfacePoint> points(double[][] p, double dz) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2] + dz));
        }
        return l;
    }

    static DispenseConfig cfg() {
        DispenseConfig c = new DispenseConfig();
        c.minPadTypeStatus = "provisional";
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, 333.1, 151.3, 1000L);
        c.needleZOffsetMm = -2.59;
        c.needleZOffsetSetAtMs = 2000L;
        c.locations.put("waste", new DispenseConfig.NamedLocation(290, 125, 5.6));
        DispenseConfig.SurfaceMap m = new DispenseConfig.SurfaceMap();
        m.points = points(ZMAP, 0);
        m.boardFingerprint = SEAT; // probed on this board registration (I4)
        c.surfaces.put("board", m);
        return c;
    }

    /** Registration of the board the surface was probed on. */
    static final String SEAT = "seat-1";

    /** Three 0603 pads of the p18_board1 run (inside the probed area), at the given board location Z. */
    static DispensePlan plan(DispenseConfig c, double boardZ) {
        PadType t = DispenseDb.seedPadTypes().get(1); // chip-0603
        t.status = PadTypeStatus.PROVISIONAL;
        t.version = 1;
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R24.1", 359.584, 131.569, 0, 0.8, 0.95, boardZ, t));
        pads.add(new PadGeometry("R1.1", 333.581, 157.339, 0, 0.8, 0.95, boardZ, t));
        pads.add(new PadGeometry("D12.1", 318.494, 144.152, 0, 0.8, 0.95, boardZ, t));
        DispensePlan p = new DispensePlanner(c, new PadTypeResolver(Collections.singletonList(t), null))
                .withSurface(DispenseService.planeOf(c, c.boardSurfaceName)).plan("ftp", pads, 290, 125);
        p.boardRegistration = SEAT;
        return p;
    }

    static InterlockReport check(DispenseConfig c, DispensePlan p) {
        return check(c, p, DispenseService.boardScope(c, p));
    }

    static InterlockReport check(DispenseConfig c, DispensePlan p, Interlocks.Scope scope) {
        return check(c, p, scope, null, null);
    }

    /** As above after a home at {@code home} and the Z check {@code z} (or none). */
    static InterlockReport check(DispenseConfig c, DispensePlan p, Interlocks.Scope scope, Long home,
            ZCheck.Result z) {
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = p;
        in.estimatedPressurisedSeconds = 10;
        in.scope = scope;
        in.lastHomeAtMs = home;
        in.zCheck = z;
        return Interlocks.check(in);
    }

    static List<String> i4(InterlockReport r) {
        List<String> l = new ArrayList<>();
        for (InterlockReport.Violation v : r.violations) {
            if (v.code.equals("I4")) {
                l.add(v.message);
            }
        }
        return l;
    }

    /**
     * rc1 review (tests), AUTO_REGISTER_DESIGN §10.2 case 22: a surface probed on the simulator (simulated) never gives
     * a dot its Z on a controller that is not the simulator: I4 refuses it there, and only there.
     */
    @Test
    public void aSurfaceProbedOnTheSimulatorIsRefusedOnlyOffTheSimulator() throws Exception {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 5.5);
        c.surfaces.get("board").simulated = true;
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = p;
        in.estimatedPressurisedSeconds = 10;
        in.scope = DispenseService.boardScope(c, p);
        assertEquals(Collections.singletonList("the 'board' surface was probed on the simulator: probe it again on this "
                + "machine"), i4(Interlocks.check(in)));
        in.simulatorConfirmed = true;
        assertTrue(Interlocks.check(in).ok(), Interlocks.check(in).toString());
    }

    @Test
    public void dotsTakeTheirZFromTheProbedPlaneAndLandAtTheBenchHeight() throws Exception {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 6.2); // the job's board Z (6.2) is not used for the needle
        assertTrue(check(c, p).ok(), check(c, p).toString());
        SurfacePlane pl = DispenseService.planeOf(c, "board");
        for (DispensePlan.PlannedDot d : p.dots) {
            assertEquals(pl.z(d.xMm, d.yMm), d.surfaceZMm, 1e-9);
            assertEquals(d.surfaceZMm - 2.59 + 0.10, d.dispenseZMm, 1e-9);
            double raw = 63 - d.dispenseZMm;
            assertEquals(65.49 - d.surfaceZMm, raw, 1e-9, "bench: dispense raw Z = 65.49 - N1 contact Z");
            assertTrue(raw > 59.90 && raw < 60.15, "raw dispense Z " + raw + " (bench p18_board1: 59.93..60.13)");
        }
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        GcodeProgram prog = GcodeStreamBuilder.forPlan(c, m, p, Collections.emptyList(), false, false, 0L);
        assertEquals(Collections.emptyList(), GcodeStreamValidator.validate(prog, c, "B",
                GcodeStreamValidator.zWindow(c, m)));
    }

    @Test
    public void placesOrSurfacesTakenInTheNeedleFrameAreRefused() {
        // review scenario: waste captured with 'Use N2 Z (tip touching)' while the offset was 0 (needle frame ~2.9)
        DispenseConfig c = cfg();
        assertTrue(check(c, null, DispenseService.shotsScope("waste", true)).ok());
        c.locations.put("waste", new DispenseConfig.NamedLocation(290, 125, 2.91));
        List<String> v = i4(check(c, null, DispenseService.shotsScope("waste", true)));
        assertTrue(v.stream().anyMatch(x -> x.contains("place 'waste'")), v.toString());
        // a cup on the scale is not a needle-frame surface: only the floor applies there
        assertTrue(check(c, null, DispenseService.shotsScope("waste", false)).ok());
        // board points taken with the needle
        c = cfg();
        c.surfaces.get("board").points = points(ZMAP, -2.59);
        v = i4(check(c, plan(c, 5.5)));
        assertTrue(v.stream().anyMatch(x -> x.contains("outside the needle-frame surface")), v.toString());
    }

    @Test
    public void noNeedleFrameOrAStaleOneIsRefused() {
        DispenseConfig c = cfg();
        c.needleAnchor = null;
        assertTrue(i4(check(c, plan(c, 5.5))).get(0).contains("needle Z frame is not set"));
        c = cfg();
        c.needleChangedAtMs = 1500L; // after the anchor (1000), before the offset (2000)
        assertTrue(i4(check(c, plan(c, 5.5))).get(0).contains("changed after the needle Z frame"));
        // the review's offset-0 case: a touch-off cannot reach the -2.59 mm frame offset
        c = cfg();
        c.needleZOffsetMm = 0.0;
        List<String> v = i4(check(c, plan(c, 5.5)));
        assertTrue(v.stream().anyMatch(x -> x.contains("away from the needle frame")), v.toString());
        assertTrue(c.isNeedleAnchored());
        c.needleZOffsetSetAtMs = 500L; // offset older than the anchor
        assertFalse(c.isNeedleTouchedOff());
    }

    @Test
    public void aBoardMustBeProbedFlatAroundEveryDotAndPlannedOnTheCurrentMap() {
        DispenseConfig c = cfg();
        c.surfaces.clear();
        DispensePlan p = plan(c, 5.5);
        assertNull(p.surfaceFingerprint);
        assertTrue(p.warnings.stream().anyMatch(w -> w.contains("no probed board surface")), p.warnings.toString());
        assertTrue(i4(check(c, p)).get(0).contains("no 'board' surface"));

        c = cfg();
        p = plan(c, 5.5);
        assertNotNull(p.surfaceFingerprint);
        // the surface was re-probed after planning
        c.surfaces.get("board").points.get(0).z += 0.01;
        assertTrue(i4(check(c, p)).stream().anyMatch(x -> x.contains("plan the board again")));

        // a dot outside the probed area (pads far from the points)
        c = cfg();
        c.surfaces.get("board").points = points(new double[][] {{309, 123, 5.5}, {369, 123, 5.5}, {339, 150, 5.5}}, 0);
        v(i4(check(c, plan(c, 5.5))), "outside the probed 'board' surface");

        // not flat enough for a plane: one point 0.2 mm high (gap budget 0.04)
        c = cfg();
        c.surfaces.get("board").points.get(4).z += 0.2;
        v(i4(check(c, plan(c, 5.5))), "not flat enough");

        // degenerate: three points on a line
        List<DispenseConfig.SurfacePoint> line = points(new double[][] {{300, 120, 5.5}, {330, 150, 5.5},
            {360, 180, 5.5}}, 0);
        assertThrows(IllegalArgumentException.class, () -> SurfacePlane.fit(line));
    }

    /** F3: a board surface belongs to the board registration it was probed on (re-seated: probe again). */
    @Test
    public void boardSurfaceBelongsToTheBoardRegistration() {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 5.5);
        assertTrue(check(c, p).ok(), check(c, p).toString());
        // an untied board surface cannot notice a re-seat: refused
        c.surfaces.get("board").boardFingerprint = null;
        v(i4(check(c, p)), "not tied to a board registration");
        // probed on an earlier seating of the board: refused
        c.surfaces.get("board").boardFingerprint = "seat-0";
        v(i4(check(c, p)), "probed on another registration of the board");
    }

    /** The purge row's spots take their Z from their own probed surface, checked like the board's. */
    @Test
    public void purgeRowSurfaceIsCheckedLikeTheBoard() {
        DispenseConfig c = cfg();
        c.purgeRow.x0Mm = 300.0;
        c.purgeRow.y0Mm = 100.0;
        c.purgeRow.spots = 16;
        DispensePlan p = plan(c, 5.5);
        v(i4(check(c, p)), "no 'blank' surface");
        c.surfaces.put("blank", GcodeStreamTest.surface(new double[][] {{295, 95, 5.45}, {340, 95, 5.45},
            {295, 105, 5.45}, {340, 105, 5.45}}));
        assertTrue(check(c, p).ok(), check(c, p).toString());
        // spots 8..15 of a row from x 320 lie at x 340..357.5, beyond the probed blank (x <= 340)
        c.purgeRow.usedSpots = 8;
        c.purgeRow.spots = 40;
        c.purgeRow.x0Mm = 320.0;
        v(i4(check(c, p)), "outside the probed 'blank' surface");
        // a blank probed with the needle (2.59 mm low) is outside the needle frame
        c = cfg();
        c.purgeRow.x0Mm = 300.0;
        c.purgeRow.y0Mm = 100.0;
        c.purgeRow.spots = 16;
        c.surfaces.put("blank", GcodeStreamTest.surface(new double[][] {{295, 95, 2.86}, {340, 95, 2.86},
            {295, 105, 2.86}, {340, 105, 2.86}}));
        v(i4(check(c, plan(c, 5.5))), "'blank' surface Z");
        // the purge dots land at the blank's plane + offset + gap
        c.surfaces.get("blank").points.forEach(pt -> pt.z = 5.45);
        List<GcodeStreamBuilder.DotSpec> purge = DispenseService.purgeSpecs(c);
        assertEquals(8, purge.size());
        for (GcodeStreamBuilder.DotSpec d : purge) {
            assertEquals(5.45 - 2.59 + 0.10, d.dispenseZ, 1e-9);
            assertEquals(GcodeStreamBuilder.PURGE_INDEX, d.index);
        }
    }

    static void v(List<String> violations, String text) {
        assertTrue(violations.stream().anyMatch(x -> x.contains(text)), violations.toString());
    }

    @Test
    public void surfacePlaneFitHullAndResiduals() {
        SurfacePlane pl = SurfacePlane.fit(points(ZMAP, 0));
        assertEquals(5, pl.pointCount);
        assertEquals(0.035, pl.maxAbsResidualMm, 0.001, "zprobe_r7.json residual");
        assertTrue(pl.maxAbsResidualMm < new DispenseConfig().surfaceMaxResidualMm, "flat enough for the 0.04 limit");
        assertEquals(0, pl.distanceOutside(340, 150), 0);
        assertEquals(10, pl.distanceOutside(363.1 + 10, 151.3), 0.1);
        assertTrue(pl.distanceOutside(250, 150) > 50);
        // the 2026-10-27 map (runs/zmap.json, before the re-seats) is too warped for the 0.04 limit
        SurfacePlane old = SurfacePlane.fit(points(new double[][] {{309.702, 123.219, 5.55}, {309.873, 152.064, 5.6},
            {310.050, 181.903, 5.7}, {338.714, 118.976, 5.475}, {338.909, 151.800, 5.5}, {347.083, 179.577, 5.6},
            {368.774, 122.682, 5.425}, {368.945, 151.527, 5.475}, {369.122, 181.366, 5.575}}, 0));
        assertTrue(old.maxAbsResidualMm > 0.04 && old.maxAbsResidualMm < 0.05, "" + old.maxAbsResidualMm);
    }

    /** Review finding: travel / hop values on the see-saw could drive a nozzle into the work. */
    @Test
    public void travelAndHopStayInsideTheSafeZone() throws Exception {
        DispenseConfig c = cfg();
        DispensePlan p = plan(c, 5.5);
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        // travelSafeZMm = 5 (a typo): raw 58, the needle 5 mm into the waste pad
        c.travelSafeZMm = 5;
        Exception e = assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, Collections.emptyList(), false, false, 0L));
        assertTrue(e.getMessage().contains("safe zone"), e.getMessage());
        // travelSafeZMm = 62 ("safer"): raw 1, N1 4.5 mm below the board
        c.travelSafeZMm = 62;
        assertThrows(IllegalStateException.class,
                () -> GcodeStreamBuilder.forPlan(c, m, p, Collections.emptyList(), false, false, 0L));
        // hopHeightMm 50: the settings refuse it, and a hop above travel Z is refused by the builder
        DispenseConfig h = cfg();
        h.hopHeightMm = 50;
        assertFalse(h.validate().isEmpty());
        GcodeStreamBuilder b = new GcodeStreamBuilder(cfg(), m, false, false);
        b.sessionStart();
        b.pressurize();
        e = assertThrows(IllegalStateException.class,
                () -> b.dot(new GcodeStreamBuilder.DotSpec(320, 130, 3.01, 40.0, 30, 0, "x")));
        assertTrue(e.getMessage().contains("above the travel Z"), e.getMessage());
        DispenseConfig g = cfg();
        g.profile.gapMm = 2;
        assertFalse(g.validate().isEmpty());
        g.profile.gapMm = 0.20; // the bench: >= 0.20 misses dots
        assertFalse(g.validate().isEmpty());
    }

    /** Review finding: no hard Z floor. The builder and, independently, the validator enforce one. */
    @Test
    public void needleFloorInTheBuilderAndOnTheRawLines() throws Exception {
        DispenseConfig c = cfg();
        ToyMapper m = new ToyMapper(290, 125, 31.5, 0);
        // floor = 5.5 - 1.0 - 2.59 - 0.1 = 1.81 (N2 Z) = raw 61.19
        assertEquals(1.81, c.needleFloorZMm(), 1e-9);
        GcodeStreamBuilder b = new GcodeStreamBuilder(c, m, false, false);
        b.sessionStart();
        b.pressurize();
        Exception e = assertThrows(IllegalStateException.class,
                () -> b.dot(new GcodeStreamBuilder.DotSpec(320, 130, 1.5, 2.5, 30, 0, "x")));
        assertTrue(e.getMessage().contains("needle floor"), e.getMessage());
        // a raw line past the floor (0.31 / 1.66 mm below it) or above travel is refused by the validator
        GcodeStreamValidator.ZWindow w = GcodeStreamValidator.zWindow(c, m);
        for (String bad : new String[] {"G1 Z61.5000 F1500", "G1 Z62.8500 F3000", "G0 X10 Z1.0000 F1500"}) {
            GcodeProgram prog = new GcodeProgram();
            prog.lines.add(new GcodeProgram.Line(bad, GcodeProgram.Marker.NONE, -9, null, 0, null));
            List<String> v = GcodeStreamValidator.validate(prog, c, "B", w);
            assertEquals(1, v.size(), bad + " " + v);
            assertTrue(v.get(0).contains("I4: raw Z"), v.toString());
        }
        GcodeProgram ok = new GcodeProgram();
        ok.lines.add(new GcodeProgram.Line("G1 Z59.9900 F3000", GcodeProgram.Marker.NONE, -9, null, 0, null));
        assertTrue(GcodeStreamValidator.validate(ok, c, "B", w).isEmpty());
    }
}
