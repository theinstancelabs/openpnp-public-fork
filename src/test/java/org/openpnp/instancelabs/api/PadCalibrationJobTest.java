package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.PlaneReport;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.SurfacePlane;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.instancelabs.vision.Dot;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The pad calibration job through the API with fake engine steps (PROFILE_CALIBRATION_DESIGN.md §14.2): the state
 * machine (running, waiting for the readings, deciding, done), implausible and late readings, skip, cancel during an
 * engine step and at the readings (the coupons kept for their readings), Finish the calibration with every record
 * fresh (no engine step), STOP between steps (nothing queued after it), the lock while it runs and its release at the
 * readings, the coupon reservation, one job at a time, and the start's refusals in §12.2's order.
 */
public class PadCalibrationJobTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    ApiContext ctx;
    FakeEngine engine;
    File boardFile;
    long attestAtMs;

    /** Engine steps that lay nothing: every dot found at the model's size (a few % apart), every probe touched. */
    static final class FakeEngine implements PadCalibrationJobs.Engine {
        final List<String> calls = new CopyOnWriteArrayList<>();
        /** The step (by its call number, 1 = first) that waits until {@link #release}, or 0. */
        volatile int holdAt;
        /** The step whose submit throws an Error on the job's thread (a bug, out of memory), or 0. */
        volatile int errorAt;
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile long epoch;
        /** The dispense service's own stop epoch (a STOP through the API moves it), added to {@link #epoch}. */
        volatile java.util.function.LongSupplier base = () -> 0L;
        volatile String aborted;
        /** The camera misses the first this many weigh dots of each coupon. */
        volatile int missWeighDots;
        /** The dose-blocks steps as the job queued them (their setup and needle token, §22.3). */
        final List<DoseBlocksRequest> requests = new CopyOnWriteArrayList<>();

        private <T> Future<T> step(String what, java.util.function.Function<RunResult, T> result) {
            calls.add(what);
            int n = calls.size();
            if (n == errorAt) {
                throw new AssertionError("a bug in the step");
            }
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
                r.kind = what;
                r.status = aborted != null ? RunResult.Status.ABORTED : RunResult.Status.OK;
                r.message = aborted;
                return result.apply(r);
            });
        }

        @Override
        public Future<SurfaceProbeResult> probeSurface(SurfaceProbeRequest req) {
            return step("probe " + req.surfaceName, r -> {
                SurfaceProbeResult res = new SurfaceProbeResult();
                res.run = r;
                res.ok = r.status == RunResult.Status.OK;
                res.message = r.message;
                List<DispenseConfig.SurfacePoint> pts = new ArrayList<>();
                for (double[] t : req.targets) {
                    pts.add(new DispenseConfig.SurfacePoint(t[0], t[1], req.nominalZ));
                }
                res.plane = PlaneReport.of(SurfacePlane.fit(pts));
                return res;
            });
        }

        @Override
        public Future<DoseBlocksResult> runDoseBlocks(DoseBlocksRequest req) {
            requests.add(req);
            return step(req.kind, r -> {
                DoseBlocksResult res = new DoseBlocksResult();
                res.run = r;
                res.ok = r.status == RunResult.Status.OK;
                res.message = r.message;
                if (!res.ok) {
                    return res;
                }
                for (DoseBlocksRequest.Block b : req.blocks) {
                    DoseBlocksResult.BlockOut o = new DoseBlocksResult.BlockOut();
                    o.netSteps = b.netSteps;
                    o.label = b.label;
                    o.couponPlace = b.couponPlace;
                    o.hopMm = b.hopMm;
                    int phases = 1600 / b.netSteps;
                    double area = DoseModel.areaMm2(DoseModel.massMg(b.netSteps));
                    for (int i = 0; i < b.xy.size(); i++) {
                        DoseBlocksResult.DotOut d = new DoseBlocksResult.DotOut();
                        d.x = b.xy.get(i)[0];
                        d.y = b.xy.get(i)[1];
                        d.phase = i % phases;
                        double a = area * (1 + 0.03 * ((i * 37) % 7 - 3) / 3.0);
                        d.dxMm = 0.01;
                        d.dyMm = -0.01;
                        d.found = DoseBlocksRequest.WEIGH.equals(req.kind) && i < missWeighDots ? null
                                : new Dot(d.dxMm, d.dyMm, DoseModel.diameterOfArea(a), a, 0.85);
                        o.dots.add(d);
                    }
                    res.blocks.add(o);
                }
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
            aborted = reason;
            release.countDown();
        }

        void awaitHeld() throws InterruptedException {
            assertTrue(held.await(30, TimeUnit.SECONDS), "the held step was queued: " + calls);
        }
    }

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        SimGuard.noteIdentity(sim.machine, List.of(SimGuard.SIMULATOR_FIRMWARE));
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        Field f = ApiServer.class.getDeclaredField("context");
        f.setAccessible(true);
        ctx = (ApiContext) f.get(api);
        engine = new FakeEngine();
        engine.base = svc::stopEpoch;
        ctx.padCalJobs.engine = engine;
        boardFile = writeBoard();
        ok(call("PUT", "/api/locations/blank", "{\"x\":290,\"y\":240,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/coupon1", "{\"x\":350,\"y\":55,\"z\":5.80}"));
        ok(call("PUT", "/api/locations/coupon2", "{\"x\":350,\"y\":90,\"z\":5.80}"));
        ok(call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}"));
        ok(call("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}"));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[272,217,5.5],[343,217,5.5],[272,273,5.5],[343,273,5.5]]}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":275,\"y0Mm\":220,\"pitchXMm\":2.0,\"spots\":33},"
                + "\"vacuumProbe\":{\"vacuumSettleMs\":300,\"readDelayMs\":0}}"));
        ok(call("POST", "/api/screw/start-angle", "{}"));
        attestAtMs = System.currentTimeMillis();
    }

    @AfterEach
    public void after() throws Exception {
        engine.release.countDown();
        api.stop();
        svc.shutdown();
        sim.close();
    }

    /** J1-J2 SW_SPST (2 half turns per pad) and U1 WSON-8 (not dispensable). */
    File writeBoard() throws Exception {
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"padcal-job.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"30.0\" y=\"16.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        Object[][] parts = {{"J1", "SW_SPST_3.9x2.9mm", 6, 6}, {"J2", "SW_SPST_3.9x2.9mm", 18, 6},
            {"U1", "WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm", 26, 10}};
        for (Object[] p : parts) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"").append(p[0]).append("\" part-id=\"jt-")
                    .append(p[1]).append("\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(p[2]).append("\" y=\"").append(p[3])
                    .append("\" z=\"0.0\" rotation=\"0.0\"/>\n<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File file = new File(sim.configDir, "padcal-job.board.xml");
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        EdtRunner.call(() -> {
            Configuration c = Configuration.get();
            Footprint sw = PadCalibrationSimTest.footprint(PadCalibrationSimTest.pad("1", -2.0, 0, 0.864, 1.829, 18),
                    PadCalibrationSimTest.pad("2", 2.0, 0, 0.864, 1.829, 18));
            for (Object[] x : new Object[][] {{"SW_SPST_3.9x2.9mm", sw}, {"WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm",
                PadCalibrationSimTest.wson()}}) {
                Package pkg = new Package((String) x[0]);
                pkg.setFootprint((Footprint) x[1]);
                c.addPackage(pkg);
                Part part = new Part("jt-" + x[0]);
                part.setPackage(pkg);
                c.addPart(part);
            }
            c.getBoard(file);
            return null;
        }, 10_000);
        return file;
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            body = r.body();
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 3000 ? body.substring(0, 3000) + "…" : body);
        }
    }

    Res call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Res get(String path) throws Exception {
        return call("GET", path, null);
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static String str(JsonObject o, String k) {
        return PadCalibrationSimTest.str(o, k);
    }

    static String refused(Res r, String code) {
        assertEquals(409, r.status, r.toString());
        assertEquals(code, str(r.json(), "error"), r.toString());
        return str(r.json(), "reason");
    }

    String planId;
    JsonObject group;

    /** The board planned at X 150, Y 100 and its SW_SPST group. */
    void plan() throws Exception {
        JsonObject plan = ok(call("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + boardFile.getAbsolutePath()
                .replace("\\", "\\\\") + "\",\"x\":150,\"y\":100,\"z\":5.5,\"rotation\":0}"));
        planId = str(plan, "planId");
        group = PadCalibrationSimTest.one(PadCalibrationSimTest.groups(plan.getAsJsonObject("profileCheck")),
                "SW_SPST_3.9x2.9mm");
    }

    String attest() {
        return "\"regulator\":{\"psi\":18,\"atMs\":" + attestAtMs + "},\"pasteLifeAtMs\":" + attestAtMs;
    }

    String body(JsonObject g, String attest) {
        return "{\"planId\":\"" + planId + "\",\"groups\":[\"" + str(g, "group") + "\"],\"suggestionIds\":{\""
                + str(g, "group") + "\":\"" + str(g.getAsJsonObject("suggestion"), "id") + "\"}" + (attest.isEmpty()
                        ? "" : "," + attest) + "}";
    }

    JsonObject job() throws Exception {
        JsonElement e = ok(get("/api/status")).get("padCalibration");
        return e == null || e.isJsonNull() ? null : e.getAsJsonObject();
    }

    JsonObject awaitState(String... states) throws Exception {
        long until = System.currentTimeMillis() + 60_000;
        JsonObject j = null;
        while (System.currentTimeMillis() < until) {
            j = job();
            for (String s : states) {
                if (j != null && s.equals(str(j, "state"))) {
                    return j;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("not " + String.join(" or ", states) + ": " + j);
    }

    String readings(String full1, String empty1, String full2, String empty2) {
        return "{\"step\":\"readings\",\"coupons\":[{\"place\":\"coupon1\",\"fullG\":" + full1 + ",\"emptyG\":" + empty1
                + "},{\"place\":\"coupon2\",\"fullG\":" + full2 + ",\"emptyG\":" + empty2 + "}]";
    }

    // ------------------------------------------------------------------------------------------
    // The job
    // ------------------------------------------------------------------------------------------

    @Test
    public void runsWaitsForTheReadingsDecidesAndPlansAgain() throws Exception {
        plan();
        engine.holdAt = 1;
        JsonObject started = ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        String id = str(started, "id");
        assertEquals("pad calibration", str(started, "kind"));
        engine.awaitHeld();
        // the lock: every other dispense task waits while the job runs, also between its engine steps
        JsonObject running = job();
        assertEquals("running", str(running, "state"));
        assertEquals("coupons", str(running, "step"));
        assertEquals("a pad calibration is running (coupons): wait for it or press STOP", refused(call("POST",
                "/api/run", "{\"planId\":\"" + planId + "\"}"), "refused"));
        assertEquals("a pad calibration is running (coupons): wait for it or press STOP", refused(call("PUT",
                "/api/calibration-area", "{}"), "refused"));
        // no board file is read between its engine steps either (BI §3.4)
        assertEquals("Upload the board when the run has finished.", refused(call("POST",
                "/api/boards/import?file=x.kicad_pcb", "(kicad_pcb (version 20240108))"), "refused"));
        engine.release.countDown();
        JsonObject waiting = awaitState("awaiting-reading");
        assertEquals(List.of("probe coupon1", "probe coupon2", "pad-ladder", "pad-weigh"), engine.calls);
        // at the readings the lock is released; only the coupons stay reserved
        ok(call("PUT", "/api/calibration-area", "{}"));
        JsonObject probe = ok(call("POST", "/api/surfaces/coupon1/probe", "{\"wait\":true,\"targets\":[[336,46],[364,46],"
                + "[336,64]]}")).getAsJsonObject("result");
        assertEquals("REFUSED", str(probe, "status"), probe.toString());
        assertEquals("coupon1 carries 68 dots waiting to be weighed: type the readings or skip them (Calibrate › Pad "
                + "calibration)", str(probe, "message"));
        // rc1 review (PC §7.5): nothing dispenses there either: scale shots at the coupon are refused (its mass would grow)
        assertEquals("coupon1 carries 68 dots waiting to be weighed: type the readings or skip them (Calibrate › Pad "
                + "calibration)", refused(call("POST", "/api/calibrations/shots-run", "{\"locationName\":\"coupon1\","
                        + "\"rotationDeg\":360,\"count\":1}"), "refused"));
        assertEquals("a pad calibration is waiting for the readings of its coupons: type them, skip them or cancel it "
                + "first (Calibrate › Pad calibration)", refused(call("POST", "/api/pad-calibrations", body(group,
                        attest())), "refused"), "one job at a time");
        JsonObject ask = waiting.getAsJsonObject("awaiting");
        assertEquals(2, ask.getAsJsonArray("coupons").size());
        assertEquals("g", str(ask, "unit"));
        assertTrue(str(ask, "text").contains("Coupon 1 has 68 half-turn dots (about 0.072 g)"), ask.toString());
        assertEquals(2, waiting.getAsJsonArray("pendingCoupons").size());

        // implausible: 200 mg where about 72 mg was expected; nothing is written
        String why = refused(call("POST", "/api/pad-calibrations/" + id + "/answer", readings("4.300", "4.100", "4.132",
                "4.060") + "}"), "implausible");
        assertEquals("You typed 4.300 g − 4.100 g = 200 mg; about 72 mg was expected for 68 half-turn dots. Check the "
                + "unit and that the wiped coupon was weighed second, then save again to keep it.", why);
        // late: the flux dries (the confirm time set to 0 for the test)
        ok(call("PUT", "/api/config", "{\"padCalibration\":{\"readingConfirmAfterMin\":0}}"));
        assertTrue(refused(call("POST", "/api/pad-calibrations/" + id + "/answer", readings("4.172", "4.100", "4.132",
                "4.060") + "}"), "late").startsWith("The coupon waited 0 min: the flux dries, so the reading may be "
                        + "low. Save it anyway?"));
        assertEquals("awaiting-reading", str(job(), "state"), "nothing written yet");
        JsonObject done = ok(call("POST", "/api/pad-calibrations/" + id + "/answer", readings("4.172", "4.100", "4.132",
                "4.060") + ",\"confirm\":true,\"wait\":true}"));
        assertEquals("done", str(done, "state"), done.toString());
        JsonObject result = done.getAsJsonObject("result");
        assertEquals("characterized", str(result, "outcome"), result.toString());
        assertTrue(str(result, "sentence").matches("Characterized \\d\\d:\\d\\d: SW_SPST_3\\.9x2\\.9mm · 1\\.83 × 0\\.86 mm "
                + "gets 2 half-turn dots per pad, about 2\\.12 mg per pad \\(8 % less than the 2\\.29 mg target\\); planned "
                + "again\\."), str(result, "sentence"));
        assertNotNull(str(result, "newPlanId"));
        assertEquals(List.of("probe coupon1", "probe coupon2", "pad-ladder", "pad-weigh"), engine.calls,
                "no engine step after the readings");
        assertEquals(0, ok2(get("/api/coupons/pending")), "nothing left to weigh");
        JsonObject type = null;
        for (JsonElement f : ok(get("/api/pad-types")).getAsJsonArray("families")) {
            for (JsonElement t : f.getAsJsonObject().getAsJsonArray("types")) {
                if (str(t.getAsJsonObject(), "typeKey").startsWith("sw-spst")) {
                    type = t.getAsJsonObject();
                }
            }
        }
        assertEquals("characterized", str(type, "status"));
        assertEquals(2.12, type.get("massPerPadMg").getAsDouble(), 0.01);
        assertNotNull(type.getAsJsonObject("lastCalibration"), type.toString());
    }

    /**
     * Tip setups (§22.3, §22.5): the job belongs to the setup and needle token of its start: every dose-blocks step
     * carries them, and so does its record, with its method and its automatic "calibrated for" label.
     */
    @Test
    public void everyStepAndRecordCarriesTheSetupAndTheNeedleOfTheStart() throws Exception {
        ok(call("POST", "/api/needle/changed", "{\"why\":\"a new needle\"}"));
        Long token = svc.getConfig().needleChangedAtMs;
        // after a needle change K is refused (it holds for the needle it was measured with): both tips touch
        ok(call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":" + token + ",\"surfaceZ\":5.5,"
                + "\"needleContactZ\":2.91}"));
        ok(call("POST", "/api/needle/offset", "{\"needleChangedAtMs\":" + token + ",\"offsetMm\":-2.59}"));
        // where it lands, kept for the 22G: a test without the landing check (its job is PadCalibrationSimTest's)
        org.openpnp.instancelabs.dispense.LandingResult l = new org.openpnp.instancelabs.dispense.LandingResult();
        l.setupKey = Tip.SEED_KEY;
        l.needleSinceMs = token;
        l.n = 8;
        svc.setLanding(l, true, token);
        attestAtMs = System.currentTimeMillis();
        plan();
        String id = str(ok(call("POST", "/api/pad-calibrations", body(group, attest()))), "id");
        JsonObject waiting = awaitState("awaiting-reading");
        assertEquals(Tip.SEED_KEY, str(waiting, "forTip"));
        assertEquals(token.longValue(), waiting.get("needleSinceMs").getAsLong());
        assertEquals(2, engine.requests.size(), "the ladder and the weighing");
        for (DoseBlocksRequest r : engine.requests) {
            assertEquals(Tip.SEED_KEY, r.forTip, r.kind);
            assertEquals(token, r.needleSinceMs, r.kind);
        }
        JsonObject done = ok(call("POST", "/api/pad-calibrations/" + id + "/answer", readings("4.172", "4.100", "4.132",
                "4.060") + ",\"confirm\":true,\"wait\":true}"));
        JsonObject g = done.getAsJsonObject("result").getAsJsonArray("groups").get(0).getAsJsonObject();
        assertEquals("characterized", str(g, "outcome"), g.toString());
        String label = str(g, "calibrationLabel");
        assertTrue(label.startsWith("SW_SPST_3.9x2.9mm"), g.toString());
        assertTrue(label.endsWith(" · " + Tip.SEED_NAME + " · Vieweg DV-5005 · 18 psi · Loctite GC50 SAC305 T5"),
                label);
        assertTrue(g.get("calibrationLabelAuto").getAsBoolean());
        assertFalse(label.equals(str(g, "label")), "the group's own name stays its label: " + g);
        org.openpnp.instancelabs.dispense.PadCalibrationRecord rec = svc.getDb().padCalibration(g.get("calibrationId")
                .getAsLong());
        assertEquals(Tip.SEED_KEY, rec.setupKey);
        assertEquals(token, rec.needleSinceMs);
        assertEquals("auto", rec.method);
    }

    @Test
    public void skipLeavesTheTypeProvisional() throws Exception {
        plan();
        String id = str(ok(call("POST", "/api/pad-calibrations", body(group, attest()))), "id");
        awaitState("awaiting-reading");
        JsonObject done = ok(call("POST", "/api/pad-calibrations/" + id + "/answer", "{\"step\":\"readings\","
                + "\"skip\":true,\"wait\":true}"));
        JsonObject result = done.getAsJsonObject("result");
        assertEquals("provisional", str(result, "outcome"), result.toString());
        assertTrue(str(result, "sentence").contains("stays provisional: the half turn was not weighed in this job"),
                str(result, "sentence"));
        assertEquals(0, ok2(get("/api/coupons/pending")), "a skipped weighing frees the coupons");
        Res wrong = call("POST", "/api/pad-calibrations/" + id + "/answer", "{\"step\":\"readings\",\"skip\":true}");
        assertEquals(409, wrong.status, "not waiting any more: " + wrong);
    }

    int ok2(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return new JsonParser().parse(r.body).getAsJsonArray().size();
    }

    @Test
    public void cancelAtTheReadingsKeepsTheCouponsAndFinishNeedsNoEngineStep() throws Exception {
        plan();
        String id = str(ok(call("POST", "/api/pad-calibrations", body(group, attest()))), "id");
        awaitState("awaiting-reading");
        JsonObject cancelled = ok(call("POST", "/api/pad-calibrations/" + id + "/cancel", null));
        assertEquals("cancelled", str(cancelled, "state"));
        assertTrue(str(cancelled.getAsJsonObject("result"), "sentence").matches("Cancelled \\d\\d:\\d\\d during readings: "
                + "the blank results were kept; the coupons still need weighing until \\d\\d:\\d\\d\\."),
                cancelled.toString());
        // the coupons wait for their readings after the job (until stale), typed one by one
        com.google.gson.JsonArray pending = new JsonParser().parse(get("/api/coupons/pending").body).getAsJsonArray();
        assertEquals(2, pending.size());
        String[][] grams = {{"4.172", "4.100"}, {"4.132", "4.060"}};
        JsonObject last = null;
        for (int i = 0; i < 2; i++) {
            JsonObject c = pending.get(i).getAsJsonObject();
            last = ok(call("POST", "/api/coupons/" + c.get("recordId").getAsLong() + "/reading", "{\"fullG\":"
                    + grams[i][0] + ",\"emptyG\":" + grams[i][1] + "}"));
        }
        assertTrue(last.get("confirmed").getAsBoolean(), "the second weighing confirms the first: " + last);
        assertEquals(0, ok2(get("/api/coupons/pending")));
        // Finish the calibration: every record fresh, nothing to dispense; it decides and plans again
        int calls = engine.calls.size();
        plan();
        JsonObject pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=" + str(group, "group")));
        assertEquals("Nothing to dispense: the blank and coupon results are still fresh; it decides and plans again.",
                str(pv, "sentence"));
        JsonObject finished = ok(call("POST", "/api/pad-calibrations", body(group, attest() + ",\"wait\":true")));
        JsonObject job = finished.getAsJsonObject("result");
        assertEquals("done", str(job, "state"), finished.toString());
        assertEquals("characterized", str(job.getAsJsonObject("result"), "outcome"), job.toString());
        assertEquals(calls, engine.calls.size(), "no engine step: " + engine.calls);
    }

    /**
     * Review 2: one coupon typed twice wrote its mass row, then failed on the second (half saved); after Cancel at
     * the readings a coupon could be typed but not skipped, as the reservation's text offers; and the mass per dot
     * was divided by the dots the camera found, where §7.5 divides by the dots laid.
     */
    @Test
    public void readingsAreCheckedBeforeAnythingIsWrittenAndALeftCouponCanBeSkipped() throws Exception {
        plan();
        engine.missWeighDots = 8;
        String id = str(ok(call("POST", "/api/pad-calibrations", body(group, attest()))), "id");
        awaitState("awaiting-reading");
        Res twice = call("POST", "/api/pad-calibrations/" + id + "/answer", "{\"step\":\"readings\",\"coupons\":["
                + "{\"place\":\"coupon1\",\"fullG\":4.172,\"emptyG\":4.100},{\"place\":\"coupon2\",\"fullG\":4.132,"
                + "\"emptyG\":4.060},{\"place\":\"coupon1\",\"fullG\":4.172,\"emptyG\":4.100}]}");
        assertEquals(400, twice.status, twice.toString());
        assertEquals("coupon1 is typed twice: type each coupon's two readings once", str(twice.json(), "reason"));
        assertEquals("awaiting-reading", str(job(), "state"));
        assertEquals(2, ok2(get("/api/coupons/pending")), "nothing was written");
        ok(call("POST", "/api/pad-calibrations/" + id + "/cancel", null));
        com.google.gson.JsonArray pending = new JsonParser().parse(get("/api/coupons/pending").body).getAsJsonArray();
        JsonObject c1 = pending.get(0).getAsJsonObject();
        JsonObject c2 = pending.get(1).getAsJsonObject();
        assertEquals("coupon1", str(c1, "place"));
        assertEquals(68, c1.get("dots").getAsInt());
        assertEquals(60, c1.get("found").getAsInt());
        // per dot laid: 72 mg over the 68 dots, not over the 60 the camera found
        JsonObject typed = ok(call("POST", "/api/coupons/" + c1.get("recordId").getAsLong() + "/reading",
                "{\"fullG\":4.172,\"emptyG\":4.100}"));
        assertEquals(72.0 / 68, typed.getAsJsonObject("record").get("mgPerDot").getAsDouble(), 1e-9);
        // the other one is skipped: no reading, and the place is free again
        assertNotNull(svc.couponReservation("coupon2"));
        JsonObject skipped = ok(call("POST", "/api/coupons/" + c2.get("recordId").getAsLong() + "/reading",
                "{\"skip\":true}"));
        assertEquals("coupon2: weighing skipped; wipe its 68 half-turn dots off before it is used again",
                str(skipped, "text"));
        assertTrue(skipped.getAsJsonObject("record").get("skipped").getAsBoolean());
        assertNull(svc.couponReservation("coupon2"));
        assertEquals(0, ok2(get("/api/coupons/pending")));
        assertEquals(404, call("POST", "/api/coupons/" + c2.get("recordId").getAsLong() + "/reading",
                "{\"skip\":true}").status, "skipped once");
    }

    @Test
    public void cancelDuringAnEngineStepAbortsIt() throws Exception {
        plan();
        // no blank surface: it is probed first (the purge row's targets, the area's corners inset 1 mm and its centre)
        ok(call("DELETE", "/api/surfaces/blank", null));
        engine.holdAt = 4;
        String id = str(ok(call("POST", "/api/pad-calibrations", body(group, attest()))), "id");
        engine.awaitHeld();
        assertEquals("ladder", str(job(), "step"));
        ok(call("POST", "/api/pad-calibrations/" + id + "/cancel", null));
        JsonObject j = awaitState("cancelled");
        assertTrue(str(j.getAsJsonObject("result"), "sentence").matches("Cancelled \\d\\d:\\d\\d during ladder: nothing "
                + "was measured\\."), j.toString());
        assertEquals(List.of("probe blank", "probe coupon1", "probe coupon2", "pad-ladder"), engine.calls,
                "no step after it");
        // the pad_calibrations row of the group says so
        com.google.gson.JsonArray rows = new JsonParser().parse(get("/api/pad-calibrations").body).getAsJsonArray();
        assertEquals("cancelled", str(rows.get(0).getAsJsonObject(), "outcome"));
    }

    @Test
    public void stopBetweenStepsQueuesNothingAfterIt() throws Exception {
        plan();
        engine.holdAt = 1;
        ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        engine.awaitHeld();
        // STOP stops the job first (under its lock), then the engine: the step running ends, no next one is queued
        ok(call("POST", "/api/stop", null));
        engine.release.countDown();
        JsonObject j = awaitState("stopped");
        assertTrue(str(j.getAsJsonObject("result"), "sentence").startsWith("Stopped "), j.toString());
        assertEquals(List.of("probe coupon1"), engine.calls, "the job's submit after the STOP does not happen");
    }

    @Test
    public void aStopWhileTheStartSavesQueuesNoEngineStep() throws Exception {
        plan();
        DispenseEndpoints dispense = new DispenseEndpoints(ctx, new MachineEndpoints(ctx));
        PadProfileEndpoints profile = new PadProfileEndpoints(ctx, dispense);
        PadCalibrationJob job = PadCalibrationJob.prepare(ctx, profile, dispense, svc, PadCalibrationEndpoints.request(
                new JsonParser().parse(body(group, attest())).getAsJsonObject()));
        assertEquals(null, job.refusal, job.refusal);
        job.save();
        // STOP while the start is still saving the suggestions: no job is the current one yet, only the engine's epoch
        // moves; the job must not queue its first engine step after it
        ok(call("POST", "/api/stop", null));
        ctx.padCalJobs.start(job);
        JsonObject j = awaitState("stopped");
        assertTrue(str(j.getAsJsonObject("result"), "sentence").matches("Stopped \\d\\d:\\d\\d during coupons: nothing "
                + "was measured\\."), j.toString());
        assertTrue(engine.calls.isEmpty(), "no engine step after the STOP: " + engine.calls);
    }

    /**
     * Review 2: a step the job gives up waiting for (here it never ends; on the machine its task can wait behind a busy
     * machine thread) is stopped as by STOP: it must not lay its dots later, when no job records them, nor go on alone.
     */
    @Test
    public void aStepThatNeverEndsIsStoppedWhenTheJobGivesUpOnIt() throws Exception {
        plan();
        engine.holdAt = 1;
        DispenseEndpoints dispense = new DispenseEndpoints(ctx, new MachineEndpoints(ctx));
        PadProfileEndpoints profile = new PadProfileEndpoints(ctx, dispense);
        PadCalibrationJob job = PadCalibrationJob.prepare(ctx, profile, dispense, svc, PadCalibrationEndpoints.request(
                new JsonParser().parse(body(group, attest())).getAsJsonObject()));
        assertEquals(null, job.refusal, job.refusal);
        job.stepWaitMs = 300;
        job.save();
        ctx.padCalJobs.start(job);
        JsonObject j = awaitState("failed");
        assertEquals("the coupons step did not end within 0 min: the engine was stopped", str(j.getAsJsonObject(
                "result"), "refusal"), j.toString());
        assertEquals("the pad calibration gave up waiting for its coupons step", engine.aborted);
        assertEquals(List.of("probe coupon1"), engine.calls, "no step after it");
    }

    /**
     * Review 1 (machine safety): an Error on the job's thread (a bug, out of memory) fails the job as an exception
     * does. Before, it left the job "running" for good: every other dispense task was refused, and a STOP could not
     * end it.
     */
    @Test
    public void anErrorFailsTheJobAndReleasesItsLock() throws Exception {
        plan();
        engine.errorAt = 1;
        ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        JsonObject j = awaitState("failed");
        assertEquals("an internal error stopped the calibration (AssertionError: a bug in the step); see the OpenPnP "
                + "log", str(j.getAsJsonObject("result"), "refusal"), j.toString());
        assertEquals(List.of("probe coupon1"), engine.calls, "no step after it");
        // the lock is released: the other dispense tasks may start, and so may the next job (on the suggestion now:
        // the failed job's record changed it)
        ok(call("PUT", "/api/calibration-area", "{}"));
        engine.errorAt = 0;
        plan();
        ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        awaitState("awaiting-reading");
    }

    @Test
    public void theEngineWritesCharacterizedOnlyWithEveryCheckAndNeverValidated() throws Exception {
        org.openpnp.instancelabs.dispense.PadType t = svc.getDb().currentPadType("chip-0805", Tip.SEED_KEY).copy();
        t.status = org.openpnp.instancelabs.dispense.PadTypeStatus.CHARACTERIZED;
        org.openpnp.instancelabs.dispense.PadCalibrationRecord rec = new org.openpnp.instancelabs.dispense
                .PadCalibrationRecord();
        rec.jobId = "pc-test";
        rec.typeKey = "chip-0805";
        rec.outcome = org.openpnp.instancelabs.dispense.PadCalibrationRecord.CHARACTERIZED;
        rec.checks.add(new org.openpnp.instancelabs.dispense.PadCalibrationRecord.Check("optical", true, "ok"));
        rec.checks.add(new org.openpnp.instancelabs.dispense.PadCalibrationRecord.Check("mass", false, "weighed once"));
        int before = svc.getDb().currentPadType("chip-0805", Tip.SEED_KEY).version;
        try {
            svc.applyPadCalibration(rec, t);
            throw new AssertionError("a failed check cannot write characterized");
        }
        catch (IllegalArgumentException e) {
            assertEquals("characterized needs every check of the calibration passed", e.getMessage());
        }
        rec.checks.get(1).pass = true;
        t.status = org.openpnp.instancelabs.dispense.PadTypeStatus.VALIDATED;
        try {
            svc.applyPadCalibration(rec, t);
            throw new AssertionError("a calibration never writes validated");
        }
        catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().startsWith("a pad calibration never writes validated"), e.getMessage());
        }
        assertEquals(before, svc.getDb().currentPadType("chip-0805", Tip.SEED_KEY).version, "nothing was written");
        t.status = org.openpnp.instancelabs.dispense.PadTypeStatus.CHARACTERIZED;
        org.openpnp.instancelabs.dispense.PadCalibrationRecord stored = svc.applyPadCalibration(rec, t);
        assertEquals(Integer.valueOf(before + 1), stored.versionTo);
        assertEquals("characterized", svc.getDb().currentPadType("chip-0805", Tip.SEED_KEY).status.dbName);
    }

    // ------------------------------------------------------------------------------------------
    // §12.2: the refusals before the start, in order
    // ------------------------------------------------------------------------------------------

    @Test
    public void refusesBeforeTheStartInTheOrderOfTheDesign() throws Exception {
        plan();
        Map<String, java.util.List<JsonObject>> groups = PadCalibrationSimTest.groups(ok(get("/api/pad-profile/check"
                + "?planId=" + planId)));
        JsonObject tip = groups.get("WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm").get(0);
        assertEquals("Nothing to calibrate: every chosen group cannot be dispensed with the 22G bent taper.",
                refused(call("POST", "/api/pad-calibrations", body(tip, attest())), "refused"));
        // the suggestion ids are checked before the attestations
        String stale = body(group, "").replace(str(group.getAsJsonObject("suggestion"), "id"), "s000000000000");
        Res s = call("POST", "/api/pad-calibrations", stale);
        refused(s, "suggestion-stale");
        assertNotNull(s.json().getAsJsonObject("profileCheck"));
        assertEquals("Read the regulator first: it must show 18.0 psi (Run › Checks › Regulator).",
                refused(call("POST", "/api/pad-calibrations", body(group, "\"pasteLifeAtMs\":" + attestAtMs)), "refused"));
        assertEquals("Read the regulator first: it must show 18.0 psi (Run › Checks › Regulator).",
                refused(call("POST", "/api/pad-calibrations", body(group, attest().replace("\"psi\":18", "\"psi\":16"))),
                        "refused"), "a reading of another pressure");
        assertEquals("Confirm the paste is within its working life first (Run › Checks).", refused(call("POST",
                "/api/pad-calibrations", body(group, "\"regulator\":{\"psi\":18,\"atMs\":" + attestAtMs + "}")),
                "refused"));
        ok(call("PUT", "/api/calibration-area", "{\"x0Mm\":290,\"y0Mm\":240,\"widthMm\":5,\"heightMm\":20}"));
        String area = refused(call("POST", "/api/pad-calibrations", body(group, attest())), "refused");
        assertTrue(area.startsWith("The calibration area has 20.0 mm left and this calibration needs "), area);
        ok(call("PUT", "/api/calibration-area", "{\"x0Mm\":135,\"y0Mm\":95,\"widthMm\":40,\"heightMm\":20}"));
        assertEquals("The calibration area overlaps the pads of padcal-job.board.xml (3 mm around them): move it "
                + "(Calibrate › Pad calibration).", refused(call("POST", "/api/pad-calibrations", body(group, attest())),
                        "refused"));
        ok(call("PUT", "/api/calibration-area", "{}"));
        ok(call("PUT", "/api/config", "{\"padCalibration\":{\"couponPlaces\":[\"scaleA\",\"scaleB\"]}}"));
        assertEquals("Set the weigh coupon places first: Setup › Places › scaleA and scaleB (where the coupons lie).",
                refused(call("POST", "/api/pad-calibrations", body(group, attest())), "refused"));
        ok(call("PUT", "/api/config", "{\"padCalibration\":{\"couponPlaces\":[\"coupon1\",\"coupon2\"]},"
                + "\"purgeRow\":{\"spots\":20}}"));
        // review 2: a coupon on the purge row (its dots and the needle's Z are the blank's) or on the board
        ok(call("PUT", "/api/locations/coupon1", "{\"x\":255,\"y\":215,\"z\":5.80}"));
        assertEquals("The weigh coupon place coupon1 overlaps the purge row: move it (Setup › Places › coupon1).",
                refused(call("POST", "/api/pad-calibrations", body(group, attest())), "refused"));
        ok(call("PUT", "/api/locations/coupon1", "{\"x\":165,\"y\":108,\"z\":5.80}"));
        assertEquals("The weigh coupon place coupon1 overlaps the pads of padcal-job.board.xml (3 mm around them): "
                + "move it (Setup › Places › coupon1).", refused(call("POST", "/api/pad-calibrations", body(group,
                        attest())), "refused"));
        ok(call("PUT", "/api/locations/coupon1", "{\"x\":350,\"y\":55,\"z\":5.80}"));
        // 8 + 0 for the ladder's one dose; 8 + 5 for the weigh dots (a switch between the two coupons)
        assertEquals("I9: only 20 unused purge spot(s) left, 21 needed: wipe the purge row and reset it",
                refused(call("POST", "/api/pad-calibrations", body(group, attest())), "refused"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"spots\":33}}"));
        assertTrue(engine.calls.isEmpty(), "nothing was started: " + engine.calls);
        // the preview names the same refusal and what it would do
        JsonObject pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=all"));
        assertEquals("Read the regulator first: it must show 18.0 psi (Run › Checks › Regulator).", str(pv, "refusal"));
        assertTrue(str(pv, "sentence").contains("Lays 28 test dots on the blank (28 half-turn)"), str(pv, "sentence"));
        assertTrue(str(pv, "sentence").contains("Then lays 68 half-turn dots on coupon 1 and 68 half-turn dots on "
                + "coupon 2"), str(pv, "sentence"));
        ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        awaitState("awaiting-reading");
        ArrayList<String> all = new ArrayList<>(engine.calls);
        assertEquals(4, all.size());
    }

    /**
     * Review 2: the shortened statistics only a SIM test may use are refused when the machine calibrates, not only
     * when they are saved: PUT /api/config keeps a value that is already below its floor (a configuration carried
     * over from the simulator), and the job would lay a 4-dot ladder and decide on it.
     */
    @Test
    public void theSimulatorsShortStatisticsAreRefusedOnTheMachine() throws Exception {
        plan();
        ok(call("PUT", "/api/config", "{\"padCalibration\":{\"ladderDotsPerPhase\":2,\"minLadderDots\":4}}"));
        String preview = "/api/pad-calibrations/preview?planId=" + planId + "&groups=all";
        assertTrue(str(ok(get(preview)), "sentence").contains("Lays 4 test dots on the blank"), "the SIM's ladder");
        // the same configuration with a controller that is not the simulator
        SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:Marlin 2.1"));
        assertFalse(svc.isSimulatorConfirmed());
        assertEquals("padCalibration.ladderDotsPerPhase below 12 is allowed in the simulator only; padCalibration"
                + ".minLadderDots below 28 is allowed in the simulator only: set them back to calibrate on this machine",
                refused(call("POST", "/api/pad-calibrations", body(group, attest())), "refused"));
        assertTrue(engine.calls.isEmpty(), "nothing was started: " + engine.calls);
    }

    /** The blank probe's touches of a preview: [x, y]. */
    static List<double[]> blankProbes(JsonObject pv) {
        List<double[]> l = new ArrayList<>();
        for (JsonElement e : pv.getAsJsonArray("probes")) {
            JsonObject p = e.getAsJsonObject();
            if ("blank".equals(str(p, "surface"))) {
                l.add(new double[] {p.get("x").getAsDouble(), p.get("y").getAsDouble()});
            }
        }
        return l;
    }

    /**
     * Review 2, the blank step: the surface its own touches make covers the calibration area for the next job (it
     * touches the area's corners inset 1 mm, √2 mm from the corners themselves), and N1 never touches the band of the
     * area where earlier ladders left their dots (the area's lower corners and centre, and the purge-row target past
     * the blank place, lay in it).
     */
    @Test
    public void theBlankProbeCoversTheAreaForTheNextJobAndStaysOffItsUsedBand() throws Exception {
        plan();
        ok(call("DELETE", "/api/surfaces/blank", null));
        JsonObject pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=all"));
        assertTrue(pv.getAsJsonObject("blank").get("probe").getAsBoolean(), pv.getAsJsonObject("blank").toString());
        StringBuilder pts = new StringBuilder();
        for (double[] p : blankProbes(pv)) {
            pts.append(pts.length() == 0 ? "" : ",").append("[").append(p[0]).append(",").append(p[1]).append(",5.5]");
        }
        // the surface as that probe leaves it: the next job needs no probe of its own
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[" + pts + "]}"));
        pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=all"));
        assertFalse(pv.getAsJsonObject("blank").get("probe").getAsBoolean(), pv.getAsJsonObject("blank").toString());

        // 11 mm of the area used by earlier ladders (paste), then a reconnect: the probe stays above that band
        DispenseConfig c = svc.getConfig();
        c.calibrationArea.usedMm = 11;
        svc.updateConfig(c);
        ok(call("DELETE", "/api/surfaces/blank", null));
        pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=all"));
        JsonObject area = ok(get("/api/calibration-area")).getAsJsonObject("effective");
        double x0 = area.get("x0Mm").getAsDouble(), x1 = x0 + area.get("widthMm").getAsDouble();
        double y0 = area.get("y0Mm").getAsDouble(), used = y0 + 11;
        assertEquals(used, pv.getAsJsonObject("blank").get("y0").getAsDouble(), 1e-6, "the ladder starts above it");
        List<double[]> touches = blankProbes(pv);
        assertTrue(touches.size() >= 5, pv.toString());
        for (double[] p : touches) {
            assertFalse(p[0] > x0 - 1 && p[0] < x1 + 1 && p[1] > y0 - 1 && p[1] < used + 1, String.format(
                    java.util.Locale.ROOT, "N1 touches X %.2f, Y %.2f, in the used band Y %.1f..%.1f", p[0], p[1], y0,
                    used));
        }
    }

    /**
     * Review 2: under homeBeforeRun "always" every engine step homes first, so the weigh step would lay its dots
     * after a home that came after the coupons were touched; the engine refuses that coupon Z (I4), and the start
     * says so before the ladder spends paste.
     */
    @Test
    public void homingBeforeEveryStepRefusesAWeighingAtTheStart() throws Exception {
        plan();
        ok(call("PUT", "/api/config", "{\"homeBeforeRun\":\"always\"}"));
        // review 2: the estimate counts a home before every engine step (the blank, two coupons, ladder, weighing)
        JsonObject pv = ok(get("/api/pad-calibrations/preview?planId=" + planId + "&groups=" + str(group, "group")));
        assertTrue(str(pv, "sentence").startsWith("Homes before each of its 5 steps (the setting is: always home "
                + "first; ≈ "), str(pv, "sentence"));
        assertEquals(5, pv.get("homes").getAsInt(), "the homes the steps table shows");
        long steps = 0;
        for (JsonElement e : pv.getAsJsonArray("steps")) {
            steps += e.getAsJsonObject().get("estimatedSeconds").getAsLong();
        }
        assertEquals(steps + 5 * pv.getAsJsonObject("home").get("estimateS").getAsDouble() + pv.getAsJsonObject(
                "zCheck").get("estimateS").getAsDouble(), pv.get("estimatedSeconds").getAsDouble(), 3, pv.toString());
        assertEquals("homeBeforeRun is 'always': every step homes first, so the coupons' Z would be from before the "
                + "weigh step's home: set homeBeforeRun to 'whenNeeded' to weigh coupons", refused(call("POST",
                        "/api/pad-calibrations", body(group, attest())), "refused"));
        assertTrue(engine.calls.isEmpty(), "nothing was started: " + engine.calls);
        ok(call("PUT", "/api/config", "{\"homeBeforeRun\":\"whenNeeded\"}"));
        ok(call("POST", "/api/pad-calibrations", body(group, attest())));
        awaitState("awaiting-reading");
    }
}
