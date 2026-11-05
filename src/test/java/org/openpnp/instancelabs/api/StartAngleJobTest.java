package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseProfile;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.vision.Dot;

import com.google.gson.JsonObject;

/**
 * BR-77: the start-angle measure job with fake engine steps (PadCalibrationJobTest's machine and API): a STOP the job did
 * not see itself (OpenPnP's own Stop moves the engine's stop epoch) after its last step sets nothing.
 */
public class StartAngleJobTest {
    final PadCalibrationJobTest t = new PadCalibrationJobTest();
    final Steps steps = new Steps();

    /** Dose-blocks steps that find every dot at its own count, with the areas of a flow peaking at 179.1 (a 0.47). */
    static final class Steps implements PadCalibrationJobs.Engine {
        final AtomicInteger calls = new AtomicInteger();
        final List<DoseBlocksRequest> reqs = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** The step (1 = first) that waits until {@link #release}, or 0. */
        volatile int holdAt;
        final CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile long epoch;
        volatile LongSupplier base = () -> 0L;

        @Override
        public Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest r) {
            throw new UnsupportedOperationException("a measure probes nothing");
        }

        @Override
        public Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest req) {
            int n = calls.incrementAndGet();
            reqs.add(req);
            return CompletableFuture.supplyAsync(() -> {
                if (n == holdAt) {
                    held.countDown();
                    try {
                        release.await(60, TimeUnit.SECONDS);
                    }
                    catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                RunResult r = new RunResult();
                r.runId = UUID.randomUUID().toString();
                r.status = RunResult.Status.OK;
                r.startedAtMs = System.currentTimeMillis();
                DoseBlocksResult res = new DoseBlocksResult();
                res.run = r;
                res.ok = true;
                DoseBlocksRequest.Block b = req.blocks.get(0);
                DoseBlocksResult.BlockOut o = new DoseBlocksResult.BlockOut();
                o.netSteps = b.netSteps;
                long c0 = Math.round(req.startAngleDeg / 360 * 1600);
                for (int i = 0; i < b.xy.size(); i++) {
                    DoseBlocksResult.DotOut d = new DoseBlocksResult.DotOut();
                    d.x = b.xy.get(i)[0];
                    d.y = b.xy.get(i)[1];
                    d.startCount = c0 + (long) i * b.netSteps;
                    double s = DispenseProfile.angleOfCount(d.startCount, 1600);
                    double area = 1 - 2 * 0.47 / Math.PI * Math.sin(Math.toRadians(s - 179.1));
                    d.found = new Dot(0, 0, 2 * Math.sqrt(area / Math.PI), area, 0.9);
                    o.dots.add(d);
                }
                res.blocks.add(o);
                return res;
            });
        }

        @Override
        public long stopEpoch() {
            return epoch + base.getAsLong();
        }

        @Override
        public void abort(String reason) {
            epoch++;
            release.countDown();
        }
    }

    @BeforeEach
    public void before() throws Exception {
        t.before();
        steps.base = t.svc::stopEpoch;
        t.ctx.padCalJobs.engine = steps;
        t.svc.setDotImager(new DotImager() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public Frame capture(double xMm, double yMm) {
                return new Frame(new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB), 0.02, 0.02, xMm, yMm);
            }

            @Override
            public List<Dot> inspect(Frame before, Frame after) {
                return new ArrayList<>();
            }
        });
    }

    @AfterEach
    public void after() throws Exception {
        steps.release.countDown();
        t.after();
    }

    /** n spots in lines of 8, 2.5 mm apart, from (x0, y0) on the blank, as JSON pairs. */
    static String spots(double x0, double y0, int n) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(String.format(Locale.ROOT, "[%.1f,%.1f]", x0 + 2.5 * (i % 8), y0 + 2.5 * (i / 8)));
        }
        return String.join(",", l);
    }

    @Test
    public void openPnpsOwnStopAfterTheLastStepSetsNothing() throws Exception {
        long t0 = System.currentTimeMillis();
        t.svc.setStartAngle(359.1, "seeded", t0, new DispenseProfile.StartAngleFeature("halves", 179.1, t0, "pc-0"));
        steps.holdAt = 2;
        PadCalibrationJobTest.Res r = t.call("POST", "/api/screw/start-angle/measure", "{\"mode\":\"halves\","
                + "\"targets\":[" + spots(285, 245, 16) + "," + spots(285, 253, 16) + "],\"throwaways\":[[285,262],"
                + "[287.5,262],[290,262],[292.5,262]],\"apply\":true}");
        assertEquals(202, r.status, r.toString());
        String job = r.json().get("job").getAsString();
        assertTrue(steps.held.await(30, TimeUnit.SECONDS), "row 2 queued");
        steps.epoch++; // OpenPnP's own Stop: the engine's epoch moves, the step it ran still ends OK
        steps.release.countDown();
        JsonObject j = null;
        for (long until = System.currentTimeMillis() + 30_000; System.currentTimeMillis() < until; Thread.sleep(20)) {
            j = PadCalibrationJobTest.ok(t.get("/api/pad-calibrations/" + job));
            if (!"running".equals(j.get("state").getAsString())) {
                break;
            }
        }
        assertEquals("stopped", j.get("state").getAsString(), j.toString());
        DispenseProfile p = t.svc.getConfig().profile;
        assertEquals(359.1, p.startAngleDeg, 0, "nothing set");
        assertEquals("pc-0", p.startAngleFeature.runId);
        assertTrue(j.getAsJsonObject("result").getAsJsonObject("startAngle").get("applied").isJsonNull(), j.toString());
    }

    /**
     * Halves lay one throwaway set per row (Process 11-04 13:0x): the first half of the throwaways before row 1, the
     * second before row 2, each spot at its own layers, nothing raised; two throwaways on one spot are refused.
     */
    @Test
    public void halvesLayEachRowsOwnThrowawaysAtTheirOwnLayers() throws Exception {
        String halves = "{\"mode\":\"halves\",\"targets\":[" + spots(285, 245, 16) + "," + spots(285, 253, 16) + "],";
        PadCalibrationJobTest.Res r = t.call("POST", "/api/screw/start-angle/measure", halves + "\"throwaways\":[[285,262],"
                + "[287.5,262,1],[290,262,0],[292.5,262,2]],\"wait\":true}");
        assertEquals(200, r.status, r.toString());
        assertEquals(2, steps.reqs.size(), "one engine step per row");
        assertEquals("[[285.0, 262.0, 0.0], [287.5, 262.0, 0.15]]", purge(steps.reqs.get(0)), "row 1's set");
        assertEquals("[[290.0, 262.0, 0.0], [292.5, 262.0, 0.3]]", purge(steps.reqs.get(1)), "row 2's set, not raised");
        PadCalibrationJobTest.Res pv = t.call("POST", "/api/screw/start-angle/measure", halves + "\"throwaways\":[[285,"
                + "262],[287.5,262],[290,262],[292.5,262]],\"apply\":true}");
        assertEquals(409, pv.status, "no stored halves feature: " + pv);
        assertTrue(pv.json().getAsJsonObject("preview").get("sentence").getAsString().contains(", each row after its 2 "
                + "throwaways,"), pv.toString());
        // the paste it takes: each row's 16 dots at the block's half turn, its own 2 throwaways at the profile's dose
        t.svc.updateConfigAtomically(c -> {
            c.profile.netStepsPerDot = 1600;
            return c;
        });
        PadCalibrationJobTest.Res whole = t.call("POST", "/api/screw/start-angle/measure", halves + "\"throwaways\":[["
                + "285,262],[287.5,262],[290,262],[292.5,262]],\"apply\":true}");
        org.openpnp.instancelabs.padprofile.Tip tip = t.svc.mountedTip();
        assertEquals(Math.round(2 * (16 * PadCalibrationJob.countMgPerDot(tip, 800) + 2 * PadCalibrationJob
                .countMgPerDot(tip, 1600))), whole.json().getAsJsonObject("preview").get("pasteMg").getAsLong(),
                whole.toString());
        // an apply against a stored feature of the other mode says which it is
        long t0 = System.currentTimeMillis();
        t.svc.setStartAngle(298.0, "seeded", t0, new DispenseProfile.StartAngleFeature("quarters", 315, t0, "pc-0"));
        PadCalibrationJobTest.Res other = t.call("POST", "/api/screw/start-angle/measure", halves + "\"throwaways\":[["
                + "285,262],[287.5,262],[290,262],[292.5,262]],\"apply\":true}");
        assertEquals(409, other.status, other.toString());
        assertTrue(other.json().get("reason").getAsString().startsWith("the start angle was set from a quarters "
                + "measure, not a halves one: set it once from a halves measure (POST /api/screw/start-angle "
                + "{startAngleDeg, evidence: {runId}})"), other.toString());
        assertBad(halves + "\"throwaways\":[[285,262],[287.5,262],[290,262]]}", "halves take one set of throwaways per "
                + "row: an even count, 2 to 8 per row (the first half before row 1, the second before row 2), 4 to 16 in "
                + "all, not 3");
        assertBad(halves + "\"throwaways\":[[285,262],[287.5,262]]}", "4 to 16 in all, not 2");
        assertBad(halves + "\"throwaways\":[" + spots(280, 262, 8) + "," + spots(280, 266, 9) + ",[300,270]]}",
                "2 to 8 per row");
        assertBad(halves + "\"throwaways\":[[285,262],[287.5,262],[285,262],[290,262]]}", "throwaways 1 and 3 lie 0.00 "
                + "mm apart: at least the targets' pitch, 2.50 mm, or one lands on the other's wet dot");
        assertBad("{\"mode\":\"quarters\",\"targets\":[" + spots(285, 245, 16) + "],\"throwaways\":[[285,262],"
                + "[286,262]]}", "throwaways 1 and 2 lie 1.00 mm apart");
        assertEquals(2, steps.reqs.size(), "nothing ran");
    }

    /**
     * Process 11-04 16:4x: halves take at least 6 pairs. With 4 the error at each half is so uncertain that the measure
     * answers "none" 70 % of the time (the honest 95 % coverage the small-n factors give): dots and time for nothing.
     */
    @Test
    public void halvesNeedSixPairsAtLeast() throws Exception {
        String throwaways = "\"throwaways\":[[285,262],[287.5,262],[290,262],[292.5,262]]";
        for (int n : new int[] {4, 5}) {
            assertBad("{\"mode\":\"halves\",\"pairs\":" + n + ",\"targets\":[" + spots(285, 245, 2 * n) + ","
                    + spots(285, 253, 2 * n) + "]," + throwaways + "}", "lay at least 6 pairs (8 by default)");
        }
        assertEquals(0, steps.reqs.size(), "nothing ran");
        PadCalibrationJobTest.Res six = t.call("POST", "/api/screw/start-angle/measure", "{\"mode\":\"halves\",\"pairs\":6,"
                + "\"targets\":[" + spots(285, 245, 12) + "," + spots(285, 253, 12) + "]," + throwaways + ",\"wait\":true}");
        assertEquals(200, six.status, six.toString());
    }

    /** The purge spots of a step as {x, y, raise}. */
    static String purge(DoseBlocksRequest r) {
        List<String> l = new ArrayList<>();
        for (double[] p : r.purgeXy) {
            l.add(java.util.Arrays.toString(new double[] {p[0], p[1], Math.round(p[2] * 1e6) / 1e6}));
        }
        return l.toString();
    }

    void assertBad(String body, String why) throws Exception {
        PadCalibrationJobTest.Res r = t.call("POST", "/api/screw/start-angle/measure", body);
        assertEquals(400, r.status, r.toString());
        assertTrue(r.json().get("reason").getAsString().contains(why), r.toString());
    }
}
