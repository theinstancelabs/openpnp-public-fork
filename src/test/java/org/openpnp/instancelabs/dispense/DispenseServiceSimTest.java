package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.MotionPlanner.CompletionType;

/**
 * End to end on a simulated machine: DispenseService plans, interlocks, checks the pads are clean,
 * reads the B step count, streams one pressure session (purge row, then the work) through OpenPnP's
 * GcodeDriver (TCP to RecordingGcodeServer, which keeps Marlin's B step count), re-syncs OpenPnP,
 * stores the run. Numbers in the bench needle frame: N1 contacts ~5.5, the needle 2.59 mm lower.
 */
public class DispenseServiceSimTest {
    SimMachine sim;
    DispenseService svc;
    final List<String> phases = new ArrayList<>();

    /**
     * Fake top camera: always sees one 0.70 mm dot at the frame centre; bare copper (bright share
     * 0.95) except at the targets listed as dirty (paste left from an earlier run: 0.5).
     */
    static class FakeImager implements DotImager {
        int captures;
        double diameter = 0.70;
        final List<double[]> dirty = new ArrayList<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Frame capture(double x, double y) {
            captures++;
            return new Frame(null, 0.01, 0.01, x, y);
        }

        @Override
        public List<Dot> inspect(Frame before, Frame after) {
            return Arrays.asList(new Dot(0.02, -0.01, diameter, Math.PI * diameter * diameter / 4, 0.9));
        }

        @Override
        public double brightFraction(Frame f, DispenseConfig.CleanPadCheck s) {
            for (double[] d : dirty) {
                if (Math.hypot(f.xMm - d[0], f.yMm - d[1]) < 0.01) {
                    return 0.5;
                }
            }
            return 0.95;
        }
    }

    FakeImager imager = new FakeImager();

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome(); // the service sees the controller connect (I11)
        assertNotNull(svc.getControllerConnectedAtMs());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        svc.setDotImager(imager);
        svc.addListener(new DispenseListener() {
            @Override
            public void onPhase(RunResult run, String phase) {
                phases.add(phase);
            }
        });
        DispenseConfig c = svc.getConfig();
        c.locations.put("waste", new DispenseConfig.NamedLocation(80, 40, 5.5));
        c.locations.put("blank", new DispenseConfig.NamedLocation(90, 40, 5.45));
        c.minPadTypeStatus = "provisional";
        // purge row on the blank: 16 spots, two boards
        c.purgeRow.x0Mm = 88.0;
        c.purgeRow.y0Mm = 45.0;
        c.purgeRow.spots = 16;
        svc.updateConfig(c);
        // I4 needle frame: N1 at 5.5 and the needle at 2.91 on the same surface (offset -2.59)
        svc.setNeedleAnchor(5.5, 2.91, null, null);
        svc.setNeedleZOffset(-2.59, "manual", "test");
        // probed surfaces: the board (around the toy pads, Z 5.6) and the blank (Z 5.45, purge row too)
        svc.setSurface("board", pts(new double[][] {{95, 75, 5.6}, {115, 75, 5.6}, {95, 90, 5.6}, {115, 90, 5.6}}),
                "test", TOY_BOARD); // tied to the toy board's registration (I4)
        svc.setSurface("blank", pts(new double[][] {{85, 35, 5.45}, {130, 35, 5.45}, {85, 50, 5.45},
            {130, 50, 5.45}}), "test");
        PadType t = svc.getDb().currentPadType("chip-0603");
        t.status = PadTypeStatus.PROVISIONAL;
        svc.savePadType(t);
        // I11: the start angle is confirmed after this controller connect
        svc.setStartAngle(null, "confirmed after power-up");
    }

    static List<DispenseConfig.SurfacePoint> pts(double[][] p) {
        List<DispenseConfig.SurfacePoint> l = new ArrayList<>();
        for (double[] v : p) {
            l.add(new DispenseConfig.SurfacePoint(v[0], v[1], v[2]));
        }
        return l;
    }

    /** Fake squash: touch-off dots whose commanded gap <= 0.05 come out 1.0 mm wide. */
    static DotImager squashImager() {
        final double[] gapsSeen = NeedleTouchOff.gaps(new NeedleTouchOff.Request());
        return new DotImager() {
            int i = 0;
            final List<Frame> afterFrames = new ArrayList<>();

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Frame capture(double x, double y) {
                return new Frame(null, 0.01, 0.01, x, y);
            }

            @Override
            public List<Dot> inspect(Frame before, Frame after) {
                double g = gapsSeen[i++];
                double dia = g <= 0.05 + 1e-9 ? 1.0 : 0.62;
                return Arrays.asList(new Dot(0, 0, dia, 0.3, 0.9));
            }
        };
    }

    @AfterEach
    public void after() throws Exception {
        svc.shutdown();
        sim.close();
    }

    DispensePlan plan() throws Exception {
        PadType t = svc.getDb().currentPadType("chip-0603");
        List<PadGeometry> pads = new ArrayList<>();
        pads.add(new PadGeometry("R1.1", 100.0, 80.0, 0, 0.8, 0.95, 5.6, t));
        pads.add(new PadGeometry("R1.2", 101.65, 80.0, 0, 0.8, 0.95, 5.6, t));
        pads.add(new PadGeometry("R2.1", 110.0, 85.0, 90, 0.8, 0.95, 5.6, t));
        DispensePlan p = svc.newPlanner().plan("sim-toy", pads, 105.5, 45); // from the purge row's end
        p.boardRegistration = TOY_BOARD; // planned from pads, not an OpenPnP board location
        return p;
    }

    /** Registration of the toy board the probed board surface belongs to. */
    static final String TOY_BOARD = "toy-board-seat-1";

    static int count(List<String> l, String s) {
        return java.util.Collections.frequency(l, s);
    }

    /** B targets of the engine's G1 B lines, in order (forward, back per dot). */
    static List<Double> bTargets(List<String> sent) {
        List<Double> b = new ArrayList<>();
        for (String l : sent) {
            if (l.startsWith("G1 B")) {
                b.add(Double.parseDouble(l.split("\\s+")[1].substring(1)));
            }
        }
        return b;
    }

    static long steps(double b) {
        return Math.round(b * 4.44);
    }

    /** Nothing pressurised, no B move, no step-count read: refused before anything was prepared. */
    static void assertNothingStreamed(List<String> sent) {
        for (String l : sent) {
            assertFalse(l.startsWith("M106") || l.startsWith("G1 B") || l.startsWith("G92") || l.equals("M114"), l);
        }
    }

    @Test
    public void runsBoardAndResyncsOpenPnp() throws Exception {
        DispensePlan plan = plan();
        assertEquals(3, plan.dots.size());
        sim.server.setBStepCount(12345); // the screw turned since the controller's power-up
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString() + " " + r.interlocks);
        assertEquals(3, r.dotsDone, "purge dots are not counted");
        assertEquals(8, r.purgeDots);
        assertEquals(plan.profileFingerprint, r.profile.fingerprint());
        assertTrue(r.keepaliveActive, "M115 reported Cap:HOST_WATCHDOG:1");
        assertNotNull(r.dphMotion());
        assertNotNull(r.dphOverall());
        assertTrue(r.dphOverall() <= r.dphMotion());
        // I10: one look at every distinct dot before the pressure went on
        assertEquals(3, imager.captures);
        assertTrue(r.cleanCheck.ok);
        assertEquals(3, r.cleanCheck.pads.size());
        assertTrue(phases.contains("I10 clean pads: top camera at every dot (pressure off)"), phases.toString());
        // I9: the 8 purge spots are used
        assertEquals(8, svc.getConfig().purgeRow.usedSpots);
        assertEquals(8, svc.getConfig().purgeRow.spotsLeft());

        List<String> sent = sim.server.sinceMark();
        java.nio.file.Files.write(java.nio.file.Paths.get("target/sim-board-run.gcode"), sent);
        // one pressure session: purge row, cross, work
        assertEquals(1, count(sent, "M106 P2 S255"));
        assertEquals(1, count(sent, "M106 P3 S255"));
        assertEquals(1, count(sent, "M107 P3"));
        assertEquals(1, count(sent, "M990 S3000"));
        assertEquals(1, count(sent, "M990 S0"));
        assertTrue(sent.indexOf("M115") < sent.indexOf("M106 P2 S255"));
        assertTrue(sent.indexOf("M114") < sent.indexOf("G92 B0"), "the step count is read before the session");
        assertTrue(sent.indexOf("G92 B0") < sent.indexOf("M106 P2 S255"), "I2: B zeroed before the first B move");
        assertFalse(sent.contains("M18 B"));
        for (String l : sent) {
            assertFalse(l.startsWith("M106") && !l.endsWith("S255"), "no PWM: " + l);
        }
        // the phase used Marlin's count: the first work dot starts at 1324 of 1600 steps (297.9 deg)
        assertEquals(12345L, (long) r.bCountAtStart);
        assertEquals(297.9, r.workStartAngleDeg, 1e-9);
        List<Double> b = bTargets(sent);
        assertEquals(2 * 11, b.size());
        assertEquals(1324, Math.floorMod(12345 + steps(b.get(15)), 1600));
        assertEquals(524, Math.floorMod(12345 + steps(b.get(17)), 1600));
        assertEquals(12345 + steps(b.get(21)), sim.server.bStepCount(), "Marlin's count = start + every turn");
        // re-sync: OpenPnP agrees with the controller about X/Y/Z and B
        OpenPnpRawMapper m = sim.machine.execute(() -> new OpenPnpRawMapper(sim.machine, sim.n2, "b"));
        double[] d = m.currentDriver();
        assertEquals(sim.server.position('X'), d[0], 1e-4);
        assertEquals(sim.server.position('Y'), d[1], 1e-4);
        assertEquals(sim.server.position('Z'), d[2], 1e-4);
        assertEquals(0.0, sim.server.position('B'), 0);
        assertEquals(0.0, m.currentB(), 0);
        Location n2 = sim.n2.getLocation().convertToUnits(LengthUnit.Millimeters);
        DispensePlan.PlannedDot last = plan.dots.get(plan.dots.size() - 1);
        assertEquals(last.xMm, n2.getX(), 1e-6);
        assertEquals(last.yMm, n2.getY(), 1e-6);
        assertEquals(31.5, n2.getZ(), 1e-6);
        assertTrue(svc.isBZeroed());

        // OpenPnP's next own move starts from the truth: a pure Z move must not move X/Y
        sim.server.mark();
        sim.machine.execute(() -> {
            sim.n2.moveTo(n2.derive(null, null, 25.0, null));
            sim.n2.waitForCompletion(CompletionType.WaitForStillstand);
            return null;
        });
        for (String l : sim.server.sinceMark()) {
            if (l.startsWith("G1")) {
                assertFalse(l.contains(" X") || l.contains(" Y"), "unexpected XY in " + l);
            }
        }
        assertEquals(63 - 25.0, sim.server.position('Z'), 1e-4);

        // stored
        assertEquals(1, svc.getDb().recentRuns(10).size());
        assertEquals(3, svc.getDb().query("SELECT * FROM dot_log").size());
        assertTrue(phases.contains("dispensing"));
    }

    /** I10: a pad that still carries paste refuses the run before the pressure goes on. */
    @Test
    public void dirtyPadRefusesBeforeAnythingIsStreamed() throws Exception {
        DispensePlan plan = plan();
        imager.dirty.add(new double[] {101.65, 80.0}); // R1.2
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.startsWith("I10: 1 pad(s) are not clean"), r.message);
        assertTrue(r.message.contains("R1.2 (0.50)"), r.message);
        assertFalse(r.cleanCheck.ok);
        assertEquals(Arrays.asList("R1.2"), r.cleanCheck.dirtyPadIds());
        assertEquals(3, r.cleanCheck.pads.size());
        assertNothingStreamed(sim.server.sinceMark());
        assertEquals(0, svc.getConfig().purgeRow.usedSpots, "no purge spot used");
        assertEquals(0, r.dotsDone);
        // the camera-only check (no pressure) tells the operator the same, and passes once wiped
        PadCleanReport rep = svc.checkCleanPads(plan).get(60, TimeUnit.SECONDS);
        assertFalse(rep.ok);
        assertEquals(Arrays.asList("R1.2"), rep.dirtyPadIds());
        imager.dirty.clear();
        sim.server.mark();
        rep = svc.checkCleanPads(plan).get(60, TimeUnit.SECONDS);
        assertTrue(rep.ok, rep.refusal());
        assertEquals(plan.planId, rep.planId);
        assertNothingStreamed(sim.server.sinceMark());
        assertEquals(RunResult.Status.OK, svc.runPlan(plan).get(60, TimeUnit.SECONDS).status);
    }

    /**
     * I11: the B step count restarts at a controller power-up, so a start angle set before the last
     * connect is refused until it is confirmed (or measured again).
     */
    @Test
    public void startAngleFromBeforeTheLastConnectIsRefused() throws Exception {
        DispensePlan plan = plan();
        Thread.sleep(5);
        sim.machine.setEnabled(false);
        sim.enableAndHome();
        assertTrue(svc.getControllerConnectedAtMs() > svc.getConfig().profile.startAngleSetAtMs);
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.interlocks.has("I11"), r.interlocks.toString());
        assertNothingStreamed(sim.server.sinceMark());
        // confirmed (the controller was not power-cycled): the plan is still valid and runs
        DispenseProfile p = svc.setStartAngle(null, "not power-cycled");
        assertTrue(p.startAngleSetAtMs >= svc.getControllerConnectedAtMs());
        r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString() + " " + r.interlocks);
    }

    /** A plan made on another recipe is stale: anything that changes a dot changes its fingerprint. */
    @Test
    public void profileChangedAfterPlanningIsRefused() throws Exception {
        DispensePlan plan = plan();
        DispenseConfig c = svc.getConfig();
        c.profile.dwellMs = 300;
        svc.updateConfig(c);
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.contains("profile changed"), r.message);
        assertNothingStreamed(sim.server.sinceMark());
        // a new start angle is a new recipe too (and stamps the time it was set)
        DispensePlan p2 = plan();
        svc.setStartAngle(118.0, "measured");
        assertNotEquals(p2.profileFingerprint, svc.getConfig().profile.fingerprint());
        assertEquals(RunResult.Status.REFUSED, svc.runPlan(p2).get(60, TimeUnit.SECONDS).status);
        assertEquals(RunResult.Status.OK, svc.runPlan(plan()).get(60, TimeUnit.SECONDS).status);
    }

    /**
     * OpenPnP's simulator reports no B step count: on a loopback TCP link the engine takes it as 0
     * and says so (a real link without the count refuses a partial-turn profile).
     */
    @Test
    public void noStepCountOnALoopbackSimulatorIsTakenAsZero() throws Exception {
        sim.server.reportBCount = false;
        sim.server.setBStepCount(12345);
        sim.server.mark();
        RunResult r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString());
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("no M114 Count")), r.warnings.toString());
        assertEquals(0L, (long) r.bCountAtStart);
        assertEquals(297.9, r.workStartAngleDeg, 1e-9);
        List<Double> b = bTargets(sim.server.sinceMark());
        assertEquals(1324, Math.floorMod(steps(b.get(15)), 1600), "phase computed from count 0");
        assertNotEquals(1324, Math.floorMod(12345 + steps(b.get(15)), 1600), "the real screw angle differs");
    }

    /** I9: 16 spots last two boards; then the row must be wiped and reset. */
    @Test
    public void purgeRowIsConsumedAndReset() throws Exception {
        assertEquals(RunResult.Status.OK, svc.runPlan(plan()).get(60, TimeUnit.SECONDS).status);
        long count = sim.server.bStepCount();
        sim.server.mark();
        RunResult second = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, second.status, second.toString());
        // the second board continues from Marlin's count and starts at the same angle
        assertEquals(count, (long) second.bCountAtStart);
        List<Double> b = bTargets(sim.server.sinceMark());
        assertEquals(1324, Math.floorMod(count + steps(b.get(15)), 1600));
        assertEquals(16, svc.getConfig().purgeRow.usedSpots);
        sim.server.mark();
        RunResult third = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, third.status, third.toString());
        assertTrue(third.interlocks.has("I9"), third.interlocks.toString());
        assertNothingStreamed(sim.server.sinceMark());
        svc.resetPurgeRow();
        assertEquals(0, svc.getConfig().purgeRow.usedSpots);
        assertNotNull(svc.getConfig().purgeRow.resetAtMs);
        assertEquals(RunResult.Status.OK, svc.runPlan(plan()).get(60, TimeUnit.SECONDS).status);
    }

    /**
     * A real controller link (not a loopback simulator) that does not report the B step count:
     * the start angle of a half turn cannot be set, so the run is refused before the pressure goes
     * on; a whole revolution per dot does not need it.
     */
    @Test
    public void realLinkWithoutStepCountRefusesAPartialTurn() throws Exception {
        sim.server.reportBCount = false;
        sim.driver.setIpAddress("192.0.2.1"); // the open socket stays on the recording server
        try {
            sim.server.mark();
            RunResult r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
            assertTrue(r.message.startsWith("I11: the controller did not report the B step count"), r.message);
            List<String> sent = sim.server.sinceMark();
            assertTrue(sent.contains("M114"), sent.toString());
            for (String l : sent) {
                assertFalse(l.startsWith("M106") || l.startsWith("G1 B") || l.startsWith("G92"), l);
            }
            assertEquals(0, svc.getConfig().purgeRow.usedSpots);
            DispenseConfig c = svc.getConfig();
            c.profile.netStepsPerDot = 1600;
            svc.updateConfig(c);
            r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
            assertEquals(RunResult.Status.OK, r.status, r.toString());
            assertEquals(null, r.bCountAtStart);
            assertEquals(null, r.workStartAngleDeg);
            assertEquals(8, r.purgeDots);
        }
        finally {
            sim.driver.setIpAddress("127.0.0.1");
        }
    }

    /**
     * The bench relation "needle contact raw Z = 65.59 - N1 contact Z" in OpenPnP's N2 frame (z2 = 63 -
     * raw): the needle touches 2.59 mm below N1.
     */
    @Test
    public void needleContactFromTheBenchRawSum() throws Exception {
        assertEquals(2.91, svc.needleContactZFromRawSum(5.5, 65.59), 1e-9);
        assertEquals(5.6 - 2.59, svc.needleContactZFromRawSum(5.6, 65.59), 1e-9);
        DispenseConfig.NeedleAnchor a = svc.setNeedleAnchor(5.5, svc.needleContactZFromRawSum(5.5, 65.59), null, null,
                65.59);
        assertEquals(-2.59, a.offsetMm(), 1e-9);
        assertEquals(65.59, svc.getConfig().needleAnchor.rawSumMm, 0);
        assertEquals(-2.59, svc.getConfig().needleZOffsetMm, 1e-9);
        // a dot at the 0.10 gap on a 5.5 surface: raw 65.49 - 5.5
        assertEquals(65.49 - 5.5, 63 - (5.5 + svc.getConfig().needleZOffsetMm + svc.getConfig().gapMm()), 1e-9);
    }

    @Test
    public void controllerErrorTriggersI1ShutdownAndUnhomes() throws Exception {
        DispensePlan plan = plan();
        sim.server.mark();
        sim.server.failAtLine = 30; // inside the pressure session
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.ERROR, r.status, r.toString());
        List<String> sent = sim.server.sinceMark();
        java.nio.file.Files.write(java.nio.file.Paths.get("target/sim-board-error.gcode"), sent);
        int m410 = sent.lastIndexOf("M410");
        assertTrue(m410 > 30, sent.toString());
        assertEquals(Arrays.asList("M410", "M107 P3", "M107 P2", "M107 P1", "M107 P0", "M18 B", "M990 S0"),
                sent.subList(m410, m410 + 7));
        assertTrue(sent.subList(m410, sent.size()).contains("M114 ; get position"), "position read back");
        assertFalse(sim.machine.isHomed(), "M410 may lose steps: machine must be re-homed");
        assertFalse(svc.isBZeroed());
        assertEquals(8, svc.getConfig().purgeRow.usedSpots, "spots reserved before streaming stay used");
    }

    @Test
    public void interlockRefusalSendsNothing() throws Exception {
        svc.markNeedleChanged(); // I4
        DispensePlan plan = plan();
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status);
        assertTrue(r.interlocks.has("I4"), r.interlocks.toString());
        assertNothingStreamed(sim.server.sinceMark());
        assertEquals(0, imager.captures, "no clean-pad check before the interlocks pass");
    }

    @Test
    public void noWatchdogCapabilityMeansNoKeepalive() throws Exception {
        sim.server.hostWatchdogCapability = false;
        sim.server.mark();
        RunResult r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString());
        assertFalse(r.keepaliveActive);
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M990"), l);
        }
        assertEquals(1, count(sim.server.sinceMark(), "M106 P2 S255"), "one pressure session");
    }

    @Test
    public void touchOffAndLadderCalibrations() throws Exception {
        svc.setDotImager(squashImager());
        double before = svc.getConfig().needleZOffsetMm;
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        assertTrue(t.analysis.found(), t.analysis.reason);
        assertEquals(0.0625, t.analysis.contactGapMm, 1e-12);
        assertEquals(before + 0.0625, svc.getConfig().needleZOffsetMm, 1e-12);
        assertTrue(t.applied);

        svc.setDotImager(imager);
        DotLadderCalibration.Request req = new DotLadderCalibration.Request();
        req.rotationsDeg = new double[] {30, 58};
        req.repeats = 2;
        DotLadderCalibration.Result lr = svc.runDotLadder(req).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, lr.run.status, lr.run.toString());
        assertEquals(2, lr.rows.size());
        assertEquals(2, svc.getDotLadder(lr.calibrationId).size());

        RunResult shots = svc.runShots("waste", 360, 3, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, shots.status, shots.toString());
        assertEquals(3, shots.dotsDone);
    }

    static double primeRawZ(List<String> sent) {
        // the first Z down after the first valve ON is the prime at the waste pad
        int v = sent.indexOf("M106 P3 S255");
        for (String l : sent.subList(v, sent.size())) {
            if (l.startsWith("G1 Z")) {
                return Double.parseDouble(l.split("\\s+")[1].substring(1));
            }
        }
        return Double.NaN;
    }

    /**
     * Review finding: after "I changed the needle", prime, test dot and shots still dispensed with
     * the stale offset (I4 was waived for them). Now they are refused; only the touch-off itself
     * runs (named waiver), needs a fresh needle frame, and primes higher while not touched off.
     */
    @Test
    public void needleChangeBlocksEverythingButTheTouchOff() throws Exception {
        svc.markNeedleChanged();
        sim.server.mark();
        RunResult prime = svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, prime.status, prime.toString());
        assertTrue(prime.interlocks.has("I4"), prime.interlocks.toString());
        // touch-off without a new needle frame: refused too (the old frame belongs to the old needle)
        RunResult to = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), false).get(60, TimeUnit.SECONDS).run;
        assertEquals(RunResult.Status.REFUSED, to.status, to.toString());
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M106") || l.startsWith("G1"), "nothing pressurised or moved: " + l);
        }
        // new frame (the replacement needle is 0.3 mm longer): prime, ladder still refused
        svc.setNeedleAnchor(5.5, 2.61, null, null);
        assertEquals(RunResult.Status.REFUSED, svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS).status);
        DotLadderCalibration.Request lr = new DotLadderCalibration.Request();
        lr.rotationsDeg = new double[] {30};
        lr.repeats = 1;
        assertEquals(RunResult.Status.REFUSED, svc.runDotLadder(lr).get(60, TimeUnit.SECONDS).run.status);
        // the touch-off runs under its waiver; its prime is 0.5 mm higher than a normal prime
        svc.setDotImager(squashImager());
        sim.server.mark();
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        double offset = -2.89; // anchor offset
        double expected = 63 - (5.5 + offset + 0.10 + 0.5);
        assertEquals(expected, primeRawZ(sim.server.sinceMark()), 1e-4);
        assertTrue(t.run.interlocks.notes.stream().anyMatch(n -> n.contains("touch-off run")),
                t.run.interlocks.toString());
        assertTrue(svc.getConfig().isNeedleTouchedOff());
        // now a shot at the waste pad runs, at the normal height
        sim.server.mark();
        RunResult ok = svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, ok.status, ok.toString());
        assertEquals(63 - (5.5 + svc.getConfig().needleZOffsetMm + 0.10), primeRawZ(sim.server.sinceMark()), 1e-4);
    }
}
