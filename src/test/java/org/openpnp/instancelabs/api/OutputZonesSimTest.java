package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.TipCamera;
import org.openpnp.machine.reference.camera.SimulatedUpCamera;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.util.MovableUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-03 on the simulated controller: the pressure hold opens the valve only with the needle over a place where paste
 * may go (I12), parks there first when asked (the move before the valve line), the pump alone never sends a valve
 * line, and the bottom camera is forbidden whatever covers it. The status names the zone the needle is in.
 */
public class OutputZonesSimTest {
    static final double CAM_X = 250, CAM_Y = 150;

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    int lastStatus;
    String lastBody;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome();
        SimulatedUpCamera cam = new SimulatedUpCamera();
        cam.setHeadOffsets(new Location(LengthUnit.Millimeters, CAM_X, CAM_Y, 0, 0));
        sim.machine.addCamera(cam);
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ok("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}");
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    JsonObject call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        lastStatus = r.statusCode();
        lastBody = r.body();
        JsonElement e = new JsonParser().parse(r.body());
        return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + lastBody);
        return o;
    }

    JsonObject i12() throws Exception {
        for (JsonElement e : ok("GET", "/api/status", null).getAsJsonArray("interlocks")) {
            if ("I12".equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        throw new AssertionError("no I12 in the status");
    }

    void needleTo(double x, double y) throws Exception {
        sim.machine.execute(() -> {
            Location at = sim.n2.getLocation();
            MovableUtils.moveToLocationAtSafeZ(sim.n2, new Location(LengthUnit.Millimeters, x, y, at.getZ(), 0));
            return null;
        });
    }

    void stopPressure() throws Exception {
        ok("POST", "/api/pressure/stop", null);
    }

    CompletableFuture<HttpResponse<String>> async(String method, String path, String json) {
        return http.sendAsync(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    static void waitFor(BooleanSupplier done, String what) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (!done.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < end, "waited 20 s for " + what);
            Thread.sleep(10);
        }
    }

    JsonObject pressure() throws Exception {
        return ok("GET", "/api/status", null).getAsJsonObject("pressure");
    }

    /** Starts the hold with a park whose first move the controller holds for 3 s; returns once that move was sent. */
    CompletableFuture<HttpResponse<String>> startHeldInThePark(String json) throws Exception {
        sim.server.delayBeforeOkMs.put("G1", 3000L);
        CompletableFuture<HttpResponse<String>> f = async("POST", "/api/pressure/start", json);
        waitFor(() -> sim.server.sinceMark().stream().anyMatch(l -> l.startsWith("G1")), "the park's first move");
        return f;
    }

    /**
     * The pressure state agrees with the lines since the mark: "on" (with its I7 auto-off and keepalive) exactly when
     * the last valve line opened it.
     */
    void assertConsistent(String state) throws Exception {
        List<String> l = sim.server.sinceMark();
        boolean open = l.lastIndexOf("M106 P3 S255") > l.lastIndexOf("M107 P3");
        JsonObject p = pressure();
        assertEquals(state, p.get("state").getAsString(), p + " after " + l);
        assertEquals("on".equals(state), open, "the valve is " + (open ? "open" : "closed") + ": " + p + " after " + l);
        if (open) {
            assertFalse(p.get("autoOffAtMs").isJsonNull(), "the I7 auto-off is set: " + p);
            assertTrue(p.get("keepaliveArmed").getAsBoolean(), "the keepalive pings: " + p);
        }
    }

    @Test
    public void theHoldOpensTheValveOnlyOverAPlaceAndParksFirstWhenAsked() throws Exception {
        JsonObject z = i12();
        assertEquals("info", z.get("state").getAsString(), z.toString());
        assertTrue(z.get("zone").isJsonNull(), z.toString());

        sim.server.mark();
        call("POST", "/api/pressure/start", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12") && lastBody.contains("not over a place where paste may go"), lastBody);
        assertFalse(sim.server.sinceMark().stream().anyMatch(l -> l.startsWith("M106")), "nothing switched on");

        sim.server.mark();
        JsonObject on = ok("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        assertEquals("hold", on.get("mode").getAsString(), on.toString());
        List<String> l = sim.server.sinceMark();
        int valve = l.indexOf("M106 P3 S255");
        int lastMove = -1;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).startsWith("G0") || l.get(i).startsWith("G1")) {
                lastMove = i;
            }
        }
        assertTrue(lastMove >= 0 && valve > lastMove, "parked first, then the valve: " + l);
        Location at = sim.n2.getLocation();
        assertEquals(80, at.getX(), 0.01);
        assertEquals(40, at.getY(), 0.01);
        assertEquals("waste", i12().get("zone").getAsString());
        stopPressure();
    }

    /** A landing of the mounted tip set under N2's head offset now, so the programs and the hold aim with it. */
    void landing(double dx, double dy) throws Exception {
        DispenseConfig c = svc.getConfig();
        DispenseConfig.NeedleLanding l = new DispenseConfig.NeedleLanding();
        l.dxMm = dx;
        l.dyMm = dy;
        double[] ho = svc.headOffsetNow();
        l.headOffsetXMm = ho[0];
        l.headOffsetYMm = ho[1];
        l.setAtMs = System.currentTimeMillis();
        c.needleLanding = l;
        svc.updateConfig(c);
    }

    /**
     * Review (MAJOR): the hold's needle is where the paste lands, N2 plus the mounted tip's landing + trim. With a 4 mm
     * landing, N2 over the towel 17 mm from the camera puts the paste 13 mm from it, inside the camera's circle: the
     * status says so and the hold is refused.
     */
    @Test
    public void theHoldLooksWhereThePasteLands() throws Exception {
        landing(-4, 0);
        ok("PUT", "/api/locations/towel", "{\"x\":" + (CAM_X + 18) + ",\"y\":" + CAM_Y + ",\"z\":5.5}");
        needleTo(CAM_X + 17, CAM_Y);
        assertEquals("forbidden: bottom camera", i12().get("zone").getAsString());
        sim.server.mark();
        call("POST", "/api/pressure/start", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12") && lastBody.contains("bottom camera"), lastBody);
        assertFalse(sim.server.sinceMark().stream().anyMatch(s -> s.startsWith("M106")), "nothing switched on");
    }

    /** Review (MAJOR): a park puts where the paste lands (not N2) on the place: N2 beside it by the landing. */
    @Test
    public void theParkPutsThePasteOnThePlace() throws Exception {
        landing(-4, 1.5);
        ok("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        Location at = sim.n2.getLocation();
        assertEquals(80 + 4, at.getX(), 0.01, "N2 4 mm beside the waste");
        assertEquals(40 - 1.5, at.getY(), 0.01);
        assertEquals("waste", i12().get("zone").getAsString());
        stopPressure();
    }

    /**
     * Review (MAJOR), its sequence: Pressure on with park; Pressure off during the park (the controller holds the
     * move); Pressure on without park. The first start ends refused when its park ends and sends nothing; the newer
     * one switches on (over the waste the park reached) and owns the state: on, with its auto-off and pings, until
     * Pressure off vents it.
     */
    @Test
    public void aStartSwitchedOffDuringItsParkNeverActsForANewerOne() throws Exception {
        sim.towelUnderNeedle(svc); // the newer start passes I12 wherever OpenPnP shows the needle
        ok("PUT", "/api/config", "{\"pumpToValveSettleMs\":200,\"valveSettleMs\":200}");
        sim.server.mark();
        CompletableFuture<HttpResponse<String>> first = startHeldInThePark("{\"park\":\"waste\"}");
        assertEquals("off", ok("POST", "/api/pressure/stop", null).get("state").getAsString());
        // #12 final review m2: while the park still moves, a newer start is refused (OpenPnP is busy), so it can never
        // open the valve where a park that failed after OpenPnP planned it left the head
        HttpResponse<String> busy = async("POST", "/api/pressure/start", "{}").get(30, TimeUnit.SECONDS);
        assertEquals(409, busy.statusCode(), busy.body());
        assertTrue(busy.body().contains("busy"), busy.body());
        sim.server.delayBeforeOkMs.clear(); // the park goes on
        HttpResponse<String> a = first.get(60, TimeUnit.SECONDS);
        assertEquals(409, a.statusCode(), a.body());
        assertConsistent("off");
        assertFalse(sim.server.sinceMark().contains("M106 P3 S255"), "the switched-off start never opened the valve");
        // once the head is still, a newer start works and opens the valve once
        JsonObject on = ok("POST", "/api/pressure/start", "{}");
        assertEquals("on", on.get("state").getAsString(), on.toString());
        assertConsistent("on");
        assertEquals(1, java.util.Collections.frequency(sim.server.sinceMark(), "M106 P3 S255"), "one valve ON");
        JsonObject off = ok("POST", "/api/pressure/stop", null);
        assertFalse(off.has("note"), "not 'already off': " + off);
        assertConsistent("off");
    }

    /**
     * #12 final review M1: after a board run the needle stands over the board, a probed surface: a hold there would
     * put 18 psi of drool on the finished board, so Pressure on without a park is refused; parked over the waste it
     * goes on.
     */
    @Test
    public void aHoldIsRefusedOverABoardAndGoesOnOverTheWaste() throws Exception {
        double[] at = svc.needleXY();
        ok("PUT", "/api/surfaces/board", String.format(java.util.Locale.ROOT, "{\"points\":[[%.3f,%.3f,5.5],"
                + "[%.3f,%.3f,5.5],[%.3f,%.3f,5.5]]}", at[0] - 10, at[1] - 10, at[0] + 10, at[1] - 10, at[0],
                at[1] + 10));
        sim.server.mark();
        call("POST", "/api/pressure/start", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12") && lastBody.contains("park first"), lastBody);
        assertEquals("ok".equals(i12().get("state").getAsString()), false, "I12 shows the hold refused: " + i12());
        assertFalse(sim.server.sinceMark().contains("M106 P2 S255"), "nothing switched on");
        JsonObject on = ok("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        assertEquals("on", on.get("state").getAsString(), on.toString());
        stopPressure();
    }

    /** Pressure off during the park: the valve never opens, the hold is off, and the next start works. */
    @Test
    public void pressureOffDuringTheParkNeverOpensTheValve() throws Exception {
        ok("PUT", "/api/config", "{\"pumpToValveSettleMs\":200,\"valveSettleMs\":200}");
        sim.server.mark();
        CompletableFuture<HttpResponse<String>> start = startHeldInThePark("{\"park\":\"waste\"}");
        assertEquals("off", ok("POST", "/api/pressure/stop", null).get("state").getAsString());
        sim.server.delayBeforeOkMs.clear();
        HttpResponse<String> a = start.get(60, TimeUnit.SECONDS);
        assertEquals(409, a.statusCode(), a.body());
        assertFalse(sim.server.sinceMark().contains("M106 P3 S255"), "the valve never opened");
        assertConsistent("off");
        assertEquals("on", ok("POST", "/api/pressure/start", "{}").get("state").getAsString(), "over the waste now");
        stopPressure();
    }

    /** STOP during the park: the valve never opens and the hold ends off. */
    @Test
    public void aStopDuringTheParkNeverOpensTheValve() throws Exception {
        sim.server.mark();
        CompletableFuture<HttpResponse<String>> start = startHeldInThePark("{\"park\":\"waste\"}");
        ok("POST", "/api/stop", null);
        sim.server.delayBeforeOkMs.clear();
        HttpResponse<String> a = start.get(60, TimeUnit.SECONDS);
        assertEquals(409, a.statusCode(), a.body());
        assertFalse(sim.server.sinceMark().contains("M106 P3 S255"), "the valve never opened");
        assertConsistent("off");
    }

    @Test
    public void thePumpAloneNeverOpensTheValve() throws Exception {
        sim.server.mark();
        JsonObject on = ok("POST", "/api/pressure/start", "{\"mode\":\"pump\"}");
        assertEquals("pump", on.get("mode").getAsString(), on.toString());
        List<String> l = sim.server.sinceMark();
        assertTrue(l.contains("M106 P2 S255"), l.toString());
        assertFalse(l.contains("M106 P3 S255"), "no valve line: " + l);
        stopPressure();
        assertTrue(pressure().get("mode").isJsonNull(), "review (MINOR): no mode while off: " + pressure());
        call("POST", "/api/pressure/start", "{\"mode\":\"pump\",\"park\":\"waste\"}");
        assertEquals(400, lastStatus, lastBody);
    }

    void n2To(double x, double y, double z) throws Exception {
        sim.machine.execute(() -> {
            MovableUtils.moveToLocationAtSafeZ(sim.n2, new Location(LengthUnit.Millimeters, x, y, z, 0));
            return null;
        });
    }

    /**
     * Review (MINOR): the park ends at the travel height, not at the Z the needle had; a travel height outside N2's
     * safe Z zone (the see-saw would move N1 down) refuses it before it moves.
     */
    @Test
    public void theParkEndsAtTravelHeight() throws Exception {
        n2To(120, 60, 28); // N2 low (z1 35, inside its safe zone 26.5..36.5)
        ok("PUT", "/api/config", "{\"travelSafeZMm\":25}"); // z1 38
        call("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("safe Z zone"), lastBody);
        assertEquals(120, sim.n2.getLocation().getX(), 0.01, "not moved");
        ok("PUT", "/api/config", "{\"travelSafeZMm\":31.5}");
        ok("POST", "/api/pressure/start", "{\"park\":\"waste\"}");
        Location at = sim.n2.getLocation();
        assertEquals(80, at.getX(), 0.01);
        assertEquals(31.5, at.getZ(), 0.01, "at travel height");
        stopPressure();
    }

    /**
     * The review's gap: the zones apply to every engine program (runSegment). The prime with the waste set over the
     * camera is refused before a line is sent, in one entry that names the line and says what to do (not "park").
     */
    @Test
    public void aPrimeOverTheCameraIsRefusedBeforeALine() throws Exception {
        ok("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        ok("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}");
        ok("PUT", "/api/locations/waste", "{\"x\":" + (CAM_X + 2) + ",\"y\":" + CAM_Y + ",\"z\":5.5}");
        sim.server.mark();
        JsonObject run = ok("POST", "/api/prime", "{\"rotationDeg\":360,\"wait\":true}").getAsJsonObject("result");
        assertFalse("OK".equals(run.get("status").getAsString()), run.toString());
        String why = run.get("message").getAsString();
        assertTrue(why.contains("I12: 3 valve or screw lines put paste where it may not go; the first: the valve ON "
                + "at line ") && why.contains("over the bottom camera") && !why.contains("park"), why);
        assertFalse(sim.server.sinceMark().stream().anyMatch(s -> s.startsWith("M106")), "nothing switched on");
    }

    /** The bottom camera as the tip picture sees it: OpenPnP's up-looking camera's X/Y, a plain frame. */
    static final class TipCam implements TipCamera {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public double[] locationMm() {
            return new double[] {CAM_X, CAM_Y, 20};
        }

        @Override
        public DotImager.Frame capture() {
            return new DotImager.Frame(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), 0.02, 0.02, CAM_X,
                    CAM_Y);
        }
    }

    /**
     * BR-03's acceptance (the review's gap: it moved the needle by hand): a tip picture leaves the needle over the
     * bottom camera, and the hold is then refused; a park or a place over the camera does not make it allowed.
     */
    @Test
    public void theBottomCameraIsForbiddenEvenAsAParkOrAPlace() throws Exception {
        svc.setTipCamera(new TipCam());
        JsonObject pic = ok("POST", "/api/tip-setups/" + svc.mountedKey() + "/picture", "{\"wait\":true}");
        assertTrue(pic.getAsJsonObject("result").get("ok").getAsBoolean(), pic.toString());
        assertEquals("forbidden: bottom camera", i12().get("zone").getAsString());
        sim.server.mark();
        call("POST", "/api/pressure/start", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("I12: the needle (250.0, 150.0) is over the bottom camera, where the valve and the "
                + "screw stay off: park first (or start it with park: 'waste')"), lastBody);
        // a place over the camera does not make it allowed
        ok("PUT", "/api/locations/towel", "{\"x\":" + (CAM_X + 2) + ",\"y\":" + CAM_Y + ",\"z\":5.5}");
        call("POST", "/api/pressure/start", "{\"park\":\"towel\"}");
        assertEquals(409, lastStatus, lastBody);
        // review (MINOR): with a park, the text is about the park target
        assertTrue(lastBody.contains("I12: the park over 'towel' (252.0, 150.0) is over the bottom camera")
                && lastBody.contains("park over another place") && !lastBody.contains("park first"), lastBody);
        assertFalse(sim.server.sinceMark().stream().anyMatch(s -> s.startsWith("M106")), "nothing switched on");
    }
}
