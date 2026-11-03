package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
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

    @Test
    public void thePumpAloneNeverOpensTheValve() throws Exception {
        sim.server.mark();
        JsonObject on = ok("POST", "/api/pressure/start", "{\"mode\":\"pump\"}");
        assertEquals("pump", on.get("mode").getAsString(), on.toString());
        List<String> l = sim.server.sinceMark();
        assertTrue(l.contains("M106 P2 S255"), l.toString());
        assertFalse(l.contains("M106 P3 S255"), "no valve line: " + l);
        stopPressure();
        call("POST", "/api/pressure/start", "{\"mode\":\"pump\",\"park\":\"waste\"}");
        assertEquals(400, lastStatus, lastBody);
    }

    @Test
    public void theBottomCameraIsForbiddenEvenAsAParkOrAPlace() throws Exception {
        needleTo(CAM_X + 3, CAM_Y);
        assertEquals("forbidden: bottom camera", i12().get("zone").getAsString());
        sim.server.mark();
        call("POST", "/api/pressure/start", "{}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("bottom camera"), lastBody);
        // a place over the camera does not make it allowed
        ok("PUT", "/api/locations/towel", "{\"x\":" + (CAM_X + 2) + ",\"y\":" + CAM_Y + ",\"z\":5.5}");
        call("POST", "/api/pressure/start", "{\"park\":\"towel\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("bottom camera"), lastBody);
        assertFalse(sim.server.sinceMark().stream().anyMatch(s -> s.startsWith("M106")), "nothing switched on");
    }
}
