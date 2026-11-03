package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.instancelabs.vision.SpotDot;
import org.openpnp.machine.reference.driver.GcodeDriver;
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

    /**
     * Fake squash: half-turn touch-off dots come out 1.05 mm wide (steady dots, as on the real machine: 0.91-1.20 mm),
     * and 1.45 mm at commanded gaps <= 0.05.
     */
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
                double dia = g <= 0.05 + 1e-9 ? 1.45 : 1.05;
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
        // 1 ms per dwell: a warm JVM streams the 3 dots within one clock millisecond otherwise (no DPH motion)
        sim.server.delayBeforeOkMs.put("G4", 1L);
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
     * AUTO_REGISTER_DESIGN tasks-api rule: a STOP is told by the executor's flag, not by the exception.
     * A STOP that surfaces as another call's failure (here the clean-pad camera, as a home's position
     * read or an OpenPnP move would) still ends the run ABORTED with the STOP's reason, before the
     * pressure goes on.
     */
    @Test
    public void aStopThatSurfacesAsAnotherFailureEndsAborted() throws Exception {
        DispensePlan plan = plan();
        svc.setDotImager(new FakeImager() {
            @Override
            public Frame capture(double x, double y) {
                svc.abort("STOP pressed in the web UI");
                throw new IllegalStateException("camera: the machine is stopping");
            }
        });
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.ABORTED, r.status, r.toString());
        assertEquals("STOP pressed in the web UI", r.message);
        assertNothingStreamed(sim.server.sinceMark());
        assertFalse(svc.isBusy());
    }

    /**
     * Review 1 (machine safety): a STOP during the engine's home (here the web home's) makes OpenPnP's home
     * throw, and it never reports homed. The home's start is not left behind: the next clean home from
     * OpenPnP's own Home button is confirmed, so it is not taken for one a stop came during, and nothing
     * homes again because of it.
     */
    @Test
    public void aStoppedHomeLeavesNoStartForTheNextCleanHome() throws Exception {
        sim.server.delayBeforeOkMs.put("G28", 2500L);
        Field infinity = GcodeDriver.class.getDeclaredField("infinityTimeoutMilliseconds");
        infinity.setAccessible(true);
        infinity.setInt(sim.driver, 4000); // a G28 whose answer was cut off ends here (60 s otherwise)
        sim.server.mark();
        Future<Object> home = sim.machine.submit(() -> svc.homeSafely()); // as POST /api/machine/home
        long t0 = System.currentTimeMillis();
        while (sim.server.sinceMark().stream().noneMatch(l -> l.startsWith("G28"))) {
            assertTrue(System.currentTimeMillis() - t0 < 10000, "the home sends G28");
            Thread.sleep(10);
        }
        svc.abort("STOP pressed in the web UI");
        sim.machine.setEnabled(false); // the STOP's disable, past the busy machine thread
        ExecutionException e = assertThrows(ExecutionException.class, () -> home.get(30, TimeUnit.SECONDS));
        assertTrue(e.getCause() instanceof DispenseExecutor.AbortedException, String.valueOf(e.getCause()));
        assertFalse(sim.machine.isHomed());
        assertNull(svc.getHomeTracker().getHomeStartedAtMs(), "the stopped home's start is forgotten");
        // Start, then OpenPnP's own Home button: a clean home
        sim.server.delayBeforeOkMs.clear();
        infinity.setInt(sim.driver, 60000);
        sim.enableAndHome();
        HomeTracker.Plan p = svc.homePlan();
        assertFalse(p.needed, p.whyCode + ": " + p.why);
        assertEquals("homed", p.whyCode);
        assertFalse(svc.getHomeTracker().isHomeUnconfirmed());
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
        // the home makes the Z data older than it: a run checks Z with one N1 touch at a stored point
        FlatVacuum.install(sim, FlatVacuum.scene(5.45, new double[] {90, 70, 120, 95}, 5.6));
        DispenseConfig fast = svc.getConfig();
        FlatVacuum.fastTimings(fast);
        svc.updateConfig(fast);
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
        assertTrue(r.zCheck != null && r.zCheck.pass, String.valueOf(r.zCheck));
        assertFalse(r.homedFirst, "homed by the test: no home of its own");
    }

    /** A disable and a home (a new connect: the start angle is confirmed again, I11). */
    void homeAgain() throws Exception {
        Thread.sleep(5);
        sim.machine.setEnabled(false);
        sim.enableAndHome();
        svc.setStartAngle(null, "not power-cycled");
    }

    /**
     * Review 1 (machine safety), I4: home 1 moved Z by 0.80 mm (the bench's frame vs OpenPnP's); the board
     * was probed again after it, the needle frame, the touch-off and the blank were not; home 2 repeats
     * home 1. The run's Z check agrees on the board, but that board point was measured after home 1 and
     * vouches only for data of its frame: the check also touches the blank, which shows the move, and the
     * run is refused before the pressure goes on (the needle would be about 2 x 0.8 mm off).
     */
    @Test
    public void aBoardProbedAfterAHomeDoesNotVouchForTheNeedleFrameFromBeforeIt() throws Exception {
        homeAgain(); // home 1: every surface now seals 0.80 mm higher than stored
        FlatVacuum.install(sim, FlatVacuum.scene(5.45 + 0.80, new double[] {90, 70, 120, 95}, 5.6 + 0.80));
        DispenseConfig fast = svc.getConfig();
        FlatVacuum.fastTimings(fast);
        svc.updateConfig(fast);
        svc.setSurface("board", pts(new double[][] {{95, 75, 6.4}, {115, 75, 6.4}, {95, 90, 6.4}, {115, 90, 6.4}}),
                "probed after home 1", TOY_BOARD);
        homeAgain(); // home 2 repeats home 1
        DispensePlan plan = plan();
        phases.clear();
        sim.server.mark();
        RunResult r = svc.runPlan(plan).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.startsWith("I4: Z moved 0.8"), r.message);
        assertTrue(phases.contains("z check: the board") && phases.contains("z check: the blank"), phases.toString());
        List<String> sent = sim.server.sinceMark();
        assertFalse(sent.contains(ControllerCommands.PUMP_ON) || sent.contains(ControllerCommands.VALVE_ON),
                "the pressure never went on");
        assertTrue(sent.stream().noneMatch(l -> l.startsWith("G1 B")), "the screw never turned");
        assertTrue(svc.zCheckPlan(plan, false).needed);
        InterlockReport i = svc.checkInterlocks(plan, 1);
        assertTrue(i.toString().contains("Z moved 0.8"), i.toString());
    }

    /**
     * Review 1 (machine safety), I4: the way out of "Z moved" without another home. The run's Z check finds
     * the move; the refusal's remedy then works for this home: the needle frame recorded again replaces
     * the touch-off it set the offset over, the blank probed again lets the touch-off run (not before:
     * the blank's stale Z would put the needle 0.8 mm off), and the board registered again lets the run go.
     * rc1 review: the places moved too. The touch-off primes at the waste, and a cup set before the home
     * takes shots: each is refused until its Z is taken again (the prime went 0.70 mm into the waste).
     */
    @Test
    public void afterZMovedTheRemedyRecoversWithoutAnotherHome() throws Exception {
        svc.setLocation("cup", 150, 40, 5.0);
        homeAgain(); // Z moved 0.80 mm at this home: every surface seals 0.80 mm higher than stored
        FlatVacuum.install(sim, FlatVacuum.scene(5.45 + 0.80, new double[] {90, 70, 120, 95}, 5.6 + 0.80));
        DispenseConfig fast = svc.getConfig();
        FlatVacuum.fastTimings(fast);
        svc.updateConfig(fast);
        RunResult r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.startsWith("I4: Z moved 0.7"), r.message); // the board: 6.375 for 5.600
        assertTrue(r.message.endsWith("Record the needle frame again with both tips (Setup \u203a Needle Z frame), "
                + "probe the blank, take the places' Z again with N1 (Setup \u203a Places), touch off and register the "
                + "board again."), r.message);

        // 1. the needle frame again: N1 and the needle on the blank, both 0.80 mm higher now
        svc.setNeedleAnchor(5.5 + 0.80, 2.91 + 0.80, null, null);
        svc.setDotImager(squashImager());
        sim.server.mark();
        RunResult early = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true).get(60, TimeUnit.SECONDS).run;
        assertEquals(RunResult.Status.REFUSED, early.status, early.toString());
        assertTrue(early.message.contains("Z moved 0.7"), "the blank still needs its touch: " + early.message);
        assertNothingStreamed(sim.server.sinceMark());
        // 2. the blank probed again after the home
        svc.setSurface("blank", pts(new double[][] {{85, 35, 6.25}, {130, 35, 6.25}, {85, 50, 6.25},
            {130, 50, 6.25}}), "probed again after Z moved");
        // the places moved with the surfaces: the touch-off's prime at the waste and shots into the cup are refused
        sim.server.mark();
        RunResult atWaste = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true).get(60, TimeUnit.SECONDS).run;
        assertEquals(RunResult.Status.REFUSED, atWaste.status, atWaste.toString());
        assertEquals("interlocks refused: [I4: place 'waste' Z 5.500 was taken at an unknown time, before the home at "
                + ZCheck.clock(svc.getHomeTracker().getLastHomeAtMs()) + " where Z moved " + String.format(java.util.Locale
                        .ROOT, "%.2f", svc.getHomeTracker().getZCheck().deltaMm) + " mm: take its Z again with N1 touching "
                + "(Setup \u203a Places)]", atWaste.message);
        RunResult cup = svc.runShots("cup", 360, 1, false).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, cup.status, cup.toString());
        assertTrue(cup.message.contains("I4: place 'cup' Z 5.000 was taken at "), cup.message);
        assertNothingStreamed(sim.server.sinceMark());
        assertEquals(Arrays.asList("waste", "blank", "cup"), ZCheck.movedPlaces(svc.getConfig(),
                svc.getHomeTracker().getZCheck(), svc.getHomeTracker().getLastHomeAtMs()));
        svc.setLocation("waste", 80, 40, 5.5 + 0.80); // N1 touched the waste again
        // 3. the touch-off runs under its waiver
        svc.setDotImager(squashImager());
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString() + " " + t.run.interlocks);
        assertTrue(t.applied && svc.getConfig().isNeedleTouchedOff());
        // the board not registered again yet: still refused, the move named
        svc.setDotImager(imager);
        r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertTrue(r.message.contains("Z moved 0.7"), r.message);
        // 4. the board registered (probed) again: the run passes I4 and runs, with nothing left to check
        svc.setSurface("board", pts(new double[][] {{95, 75, 6.4}, {115, 75, 6.4}, {95, 90, 6.4}, {115, 90, 6.4}}),
                "registered again after Z moved", TOY_BOARD);
        sim.server.mark();
        r = svc.runPlan(plan()).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, r.status, r.toString() + " " + r.interlocks);
        assertEquals(null, r.zCheck, "every Z datum was measured after the home");
        assertEquals(1, count(sim.server.sinceMark(), "M106 P2 S255"), "one pressure session");
        // prime at the waste taken again; the cup still waits for its Z
        assertEquals(RunResult.Status.OK, svc.runShots("waste", 360, 1, true).get(60, TimeUnit.SECONDS).status);
        assertEquals(RunResult.Status.REFUSED, svc.runShots("cup", 360, 1, false).get(60, TimeUnit.SECONDS).status);
    }

    /**
     * Review 1 (machine safety), I4: an automatic probe of the blank after a home (the pad calibration's blank step,
     * or Probe the blank) replaced the blank's points, which were the only Z-check reference of the older data (the
     * needle frame has no X/Y here; the board counts only for its plan): Check Z, and so prime, the test dot and the
     * calibrations, then had no point left and asked for the needle frame again. The replaced points stay a
     * reference of their Z frame.
     */
    @Test
    public void theBlankProbedAgainAfterAHomeKeepsItsOldPointsAsTheZReference() throws Exception {
        homeAgain(); // the Z data predate this home; Z did not move
        FlatVacuum.install(sim, FlatVacuum.scene(5.45, new double[] {90, 70, 120, 95}, 5.6));
        DispenseConfig fast = svc.getConfig();
        FlatVacuum.fastTimings(fast);
        svc.updateConfig(fast);
        SurfaceProbeRequest req = new SurfaceProbeRequest();
        req.surfaceName = "blank";
        req.nominalZ = 5.45;
        req.targets = Arrays.asList(new double[] {86, 36}, new double[] {129, 36}, new double[] {86, 49},
            new double[] {129, 49});
        SurfaceProbeResult probed = svc.probeSurface(req).get(60, TimeUnit.SECONDS);
        assertTrue(probed.ok, probed.message);
        assertTrue(svc.getConfig().surface("blank").setAtMs > svc.getHomeTracker().getLastHomeAtMs());
        DispenseService.ZCheckPlan zp = svc.zCheckPlan(null, false);
        assertTrue(zp.needed && zp.reference != null, "a point is left to touch: " + zp.reference);
        assertEquals(85.0, zp.reference.x, 1e-9, "the blank's point from before the home");
        RunResult z = svc.checkZ().get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, z.status, z.toString());
        assertTrue(z.zCheck.pass && z.zCheck.x == 85.0 && z.zCheck.y == 35.0, String.valueOf(z.zCheck));
        RunResult prime = svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, prime.status, prime.toString() + " " + prime.interlocks);
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

    /**
     * rc1 review (machine safety): OpenPnP drops every task queued behind one of its own that throws (here a machine
     * task that fails after 0.5 s, as its Home meeting a limit would). The engine task dropped so never ran its
     * finish(): busy stayed set for good, STOP included, and every engine endpoint refused. STOP now ends it as ERROR
     * (nothing was sent), and the engine takes the next task.
     */
    @Test
    public void aTaskOpenPnpDroppedEndsAtTheNextStop() throws Exception {
        Future<Object> failing = sim.machine.submit(() -> {
            Thread.sleep(500);
            throw new Exception("test: a task of OpenPnP's own fails");
        });
        sim.server.mark();
        Future<RunResult> dropped = svc.runShots("waste", 360, 1, true);
        assertThrows(ExecutionException.class, () -> failing.get(10, TimeUnit.SECONDS));
        Thread.sleep(DispenseService.DROPPED_AFTER_MS + 300);
        assertFalse(dropped.isDone(), "OpenPnP removed it from its queue without running it");
        assertTrue(svc.isBusy());
        svc.abort("STOP pressed in the web UI");
        assertFalse(svc.isBusy(), "STOP ended the dropped task");
        RunResult r = svc.getLastRun();
        assertEquals(RunResult.Status.ERROR, r.status, r.toString());
        assertTrue(r.message.startsWith("OpenPnP dropped this task before it started"), r.message);
        assertTrue(dropped.isCancelled(), "a caller waiting for it learns at once");
        assertNothingStreamed(sim.server.sinceMark());
        RunResult next = svc.runShots("waste", 360, 1, true).get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, next.status, next.toString());
    }

    /**
     * rc1 review (machine safety), I1: an Error on the machine thread during a stream (on the machine an
     * OutOfMemoryError; here a listener's StackOverflowError) still ends with the stop sequence, so the pump and the
     * valve go off. Only an Exception ran the shutdown before: they stayed on, with no I7 on-time limit after it.
     */
    @Test
    public void anErrorDuringAStreamStillSwitchesThePneumaticsOff() throws Exception {
        DispenseListener bad = new DispenseListener() {
            @Override
            public void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
                throw new StackOverflowError("test: an Error on the machine thread");
            }
        };
        svc.addListener(bad);
        sim.server.mark();
        Future<RunResult> f = svc.runShots("waste", 360, 3, true);
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(60, TimeUnit.SECONDS));
        svc.removeListener(bad);
        assertTrue(e.getCause() instanceof StackOverflowError, String.valueOf(e.getCause()));
        List<String> sent = sim.server.sinceMark();
        int valve = sent.indexOf(ControllerCommands.VALVE_ON);
        int stop = java.util.Collections.indexOfSubList(sent, ControllerCommands.STOP_SEQUENCE);
        assertTrue(valve >= 0 && stop > valve, "the stop sequence after the valve opened: " + sent);
        assertFalse(svc.isBusy());
        assertEquals(RunResult.Status.ERROR, svc.getLastRun().status);
    }

    /**
     * Review 1 (machine safety), the pressure hold: a hold that went on after a task's API checks meets the
     * task on the machine thread, where every engine task looks at it again. Prime and the test dot (shots at
     * the waste pad), scale shots, the dot ladder, the touch-off and the clean-pad check refuse there, as runs
     * and the probes do: nothing is sent, the camera does not move, and the engine is not left busy.
     */
    @Test
    public void everyTaskRefusesOnTheMachineThreadWhileThePressureIsHeld() throws Exception {
        boolean[] held = {true}; // the hold went on after the API's checks, before the task's start
        svc.setPressureHoldCheck(() -> held[0]);
        svc.setLocation("cup", 150, 40, 5.0);
        DispensePlan plan = plan();
        DotLadderCalibration.Request lr = new DotLadderCalibration.Request();
        lr.rotationsDeg = new double[] {30};
        lr.repeats = 1;
        sim.server.mark();
        List<RunResult> runs = new ArrayList<>();
        runs.add(svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS));
        runs.add(svc.runShots("cup", 360, 3, false).get(60, TimeUnit.SECONDS));
        runs.add(svc.runDotLadder(lr).get(60, TimeUnit.SECONDS).run);
        runs.add(svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true).get(60, TimeUnit.SECONDS).run);
        PadCleanReport rep = svc.checkCleanPads(plan).get(60, TimeUnit.SECONDS);
        runs.add(svc.getLastRun());
        for (RunResult r : runs) {
            assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
            assertEquals("the pressure hold is on: Pressure off first", r.message);
        }
        assertTrue(rep.pads.isEmpty() && !rep.ok, "no pad looked at");
        assertEquals(0, imager.captures, "the camera never moved");
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M106") || l.startsWith("G0") || l.startsWith("G1"), "nothing pressurised or moved: "
                    + l);
        }
        assertFalse(svc.isBusy());
        // the hold off: they run
        held[0] = false;
        assertEquals(RunResult.Status.OK, svc.runShots("waste", 90, 1, true).get(60, TimeUnit.SECONDS).status);
        assertTrue(svc.checkCleanPads(plan).get(60, TimeUnit.SECONDS).ok);
    }

    /**
     * F9: a camera that finds the touch-off's dots at their spots, but the dots at gaps 0.075 and 0.050 joined by a
     * thick neck. It keeps what the engine asked: the cut, the spot radius and the spots of each frame.
     */
    static final class StrungImager implements DotImager {
        final List<List<double[]>> spots = new ArrayList<>();
        double cutMm, radiusMm;

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
            throw new AssertionError("the touch-off finds its dots at their spots");
        }

        @Override
        public List<SpotDot> inspectSpots(Frame before, Frame after, List<double[]> s, double cut, double radius) {
            spots.add(s);
            cutMm = cut;
            radiusMm = radius;
            List<SpotDot> l = new ArrayList<>();
            for (int j = 0; j < s.size(); j++) {
                l.add(j == 2 || j == 3 ? SpotDot.unmeasured("joined to the paste at another spot", Arrays.asList(5 - j))
                        : SpotDot.dot(new Dot(s.get(j)[0], s.get(j)[1], 1.05, 0.87, 0.9)));
            }
            return l;
        }
    }

    /**
     * F9 + F14 on the SIM machine: strung dots refuse the touch-off's conclusion (nothing recorded or applied), each
     * frame measures its dot among its neighbours a pitch apart with the strings cut off at 0.45 x the dot, and a
     * pitch with no room between the dots is refused before anything moves.
     */
    @Test
    public void touchOffRefusesStrungDotsAndATightPitch() throws Exception {
        StrungImager cam = new StrungImager();
        svc.setDotImager(cam);
        double offset = svc.getConfig().needleZOffsetMm;
        int rows = svc.getDb().query("SELECT id FROM needle_touchoff").size();
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        assertFalse(t.analysis.found());
        assertEquals("dots touch or are strung together: the paste at gap 0.075 mm is joined to the dot at gap 0.050 mm; "
                + "use a larger pitch", t.run.message);
        assertFalse(t.applied);
        assertEquals(null, t.newOffsetMm);
        assertEquals(offset, svc.getConfig().needleZOffsetMm, 1e-12, "the offset is kept");
        assertEquals(rows, svc.getDb().query("SELECT id FROM needle_touchoff").size(), "nothing recorded");
        assertEquals(0.45 * 1.16, cam.cutMm, 1e-9);
        assertEquals(0.8, cam.radiusMm, 1e-9, "half the default pitch of 1.6 mm");
        assertEquals(6, cam.spots.size(), "one frame per dot");
        for (int i = 0; i < 6; i++) {
            for (int j = 0; j < 6; j++) {
                assertEquals((j - i) * 1.6, cam.spots.get(i).get(j)[0], 1e-9, "frame " + i + ", spot " + j);
                assertEquals(0, cam.spots.get(i).get(j)[1], 1e-9);
            }
        }

        // F14: 1.0 mm leaves no room between half-turn dots: refused before the pressure or any move
        sim.server.mark();
        NeedleTouchOff.Request tight = new NeedleTouchOff.Request();
        tight.pitchMm = 1.0;
        RunResult r = svc.runNeedleTouchOff(tight, true).get(60, TimeUnit.SECONDS).run;
        assertEquals(RunResult.Status.REFUSED, r.status, r.toString());
        assertEquals(NeedleTouchOff.pitchProblem(tight, svc.getConfig()), r.message);
        for (String l : sim.server.sinceMark()) {
            assertFalse(l.startsWith("M106") || l.startsWith("G1") || l.startsWith("G0"), "nothing sent: " + l);
        }
    }

    /**
     * F9 review: the first two dots are droplets of the same size (a start gap too high: the real ladder's first dot
     * was a 0.37-0.43 mm droplet), then steady dots. The first steady dot read as squashed against them, and the
     * contact was applied between the droplets and it (+0.0875 here); now droplets are no reference: nothing is
     * recorded or applied.
     */
    @Test
    public void touchOffRefusesDropletsAsItsReference() throws Exception {
        double[] sizes = {0.40, 0.42, 1.02, 1.05, 1.04, 1.06};
        svc.setDotImager(new DotImager() {
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
                throw new AssertionError("the touch-off finds its dots at their spots");
            }

            @Override
            public List<SpotDot> inspectSpots(Frame before, Frame after, List<double[]> s, double cut, double radius) {
                List<SpotDot> l = new ArrayList<>();
                for (int j = 0; j < s.size(); j++) {
                    l.add(SpotDot.dot(new Dot(s.get(j)[0], s.get(j)[1], sizes[j], Math.PI / 4 * sizes[j] * sizes[j],
                            0.9)));
                }
                return l;
            }
        });
        double offset = svc.getConfig().needleZOffsetMm;
        int rows = svc.getDb().query("SELECT id FROM needle_touchoff").size();
        DispenseService.TouchOffResult t = svc.runNeedleTouchOff(new NeedleTouchOff.Request(), true)
                .get(60, TimeUnit.SECONDS);
        assertEquals(RunResult.Status.OK, t.run.status, t.run.toString());
        assertFalse(t.analysis.found(), t.analysis.reason);
        assertEquals("the first two dots (0.400 mm at gap 0.125, 0.420 mm at gap 0.100) are under 60 % of the 1.16 mm "
                + "a steady dot of this dose makes on the blank: droplets from a needle too high, so a larger dot "
                + "after them is no contact; run the ladder again from a lower start gap", t.run.message);
        assertFalse(t.applied);
        assertEquals(null, t.newOffsetMm);
        assertEquals(offset, svc.getConfig().needleZOffsetMm, 1e-12, "the offset is kept");
        assertEquals(rows, svc.getDb().query("SELECT id FROM needle_touchoff").size(), "nothing recorded");
        assertEquals(0.0875, NeedleTouchOff.analyze(NeedleTouchOff.gaps(new NeedleTouchOff.Request()), new Double[] {
            0.40, 0.42, 1.02, 1.05, 1.04, 1.06}, 0.25).contactGapMm, 1e-12, "what was applied before");
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
