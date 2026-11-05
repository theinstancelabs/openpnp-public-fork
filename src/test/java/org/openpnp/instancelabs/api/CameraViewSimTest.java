package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.machine.reference.camera.ImageCamera;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-102 on the simulated controller: the 2×2 camera view's four cameras. The USB cameras ("machine", "gauge") are
 * listed and served from {@code file:} devices in a simulated session (the route, the list, after=), never locked; off
 * the simulator only /dev/v4l/by-id/ devices (the write 400, the loader refuses); top and bottom send X-Frame-At-Ms
 * and take after=.
 */
public class CameraViewSimTest {
    /** Never a device on this computer: the tests open no real camera. */
    static final String GAUGE_BY_ID = "/dev/v4l/by-id/usb-Test_SIM_Gauge_0000-video-index0";
    static final String STILLS = "{\"usbCameras\":{\"machine\":{\"device\":\"file:machine.jpg\",\"width\":320,"
            + "\"height\":180},\"gauge\":{\"device\":\"file:gauge.jpg\",\"width\":256,\"height\":144}}}";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;
    String token;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // an agent holds the machine all along: the camera GETs are never locked
        token = ok(call("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"gauge read\"}", null))
                .get("token").getAsString();
    }

    @AfterEach
    public void after() throws Exception {
        api.stop();
        svc.shutdown();
        sim.close();
    }

    static final class Res {
        final int status;
        final byte[] body;
        final String type;
        final Long atMs;
        final String at;

        Res(HttpResponse<byte[]> r) {
            status = r.statusCode();
            body = r.body();
            type = r.headers().firstValue("Content-Type").orElse("");
            atMs = r.headers().firstValue("X-Frame-At-Ms").map(Long::parseLong).orElse(null);
            at = r.headers().firstValue("X-Frame-At").orElse(null);
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        JsonObject json() {
            return new JsonParser().parse(text()).getAsJsonObject();
        }

        BufferedImage image() throws Exception {
            return ImageIO.read(new ByteArrayInputStream(body));
        }

        @Override
        public String toString() {
            return status + " " + (type.startsWith("image/") ? type : text());
        }
    }

    Res call(String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(60));
        if (who != null) {
            b.header(MachineLock.TOKEN_HEADER, who);
        }
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    Res get(String path) throws Exception {
        return call("GET", path, null, null);
    }

    Res put(String json) throws Exception {
        return call("PUT", "/api/config", json, token);
    }

    static JsonObject ok(Res r) {
        assertEquals(200, r.status, r.toString());
        return r.json();
    }

    static void refused(int status, String text, Res r) {
        assertEquals(status, r.status, r.toString());
        assertTrue(r.text().contains(text), "'" + text + "' in " + r);
    }

    /** The camera {@code id} of GET /api/cameras, or null when it is not listed. */
    JsonObject listed(String id) throws Exception {
        for (JsonElement e : ok(get("/api/cameras")).getAsJsonArray("cameras")) {
            if (id.equals(e.getAsJsonObject().get("id").getAsString())) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    static String why(JsonObject o) {
        return o.get("why").isJsonNull() ? null : o.get("why").getAsString();
    }

    /** The controller answers M115 as OpenPnP's simulator on this connection. */
    void simulated() throws Exception {
        sim.server.firmwareName = "GcodeServer (RecordingGcodeServer)";
        SimGuard.noteIdentity(sim.machine, new DriverGcodeChannel(sim.driver).sendAndCollect("M115", 5000));
    }

    /** The two test JPEGs in the fixtures dir of this configuration. */
    void stills() throws Exception {
        Path fx = Files.createDirectories(UsbCameras.fixturesDir(sim.configDir).toPath());
        for (String n : new String[] {"machine.jpg", "gauge.jpg"}) {
            try (InputStream in = CameraViewSimTest.class.getResourceAsStream("/usbcameras/" + n)) {
                Files.copy(in, fx.resolve(n));
            }
        }
    }

    @Test
    public void theUsbCamerasAreListedAndServedFromStillsInASimulatedSession() throws Exception {
        assertEquals(null, listed("machine"), "no camera without its setting");
        refused(404, "no gauge camera", get("/api/cameras/gauge/frame.jpg"));
        simulated();
        stills();
        ok(put(STILLS));
        JsonObject gauge = listed("gauge");
        assertNotNull(gauge);
        assertEquals("Gauge", gauge.get("name").getAsString());
        assertEquals("usb", gauge.get("kind").getAsString());
        assertTrue(gauge.get("present").getAsBoolean(), gauge.toString());
        assertEquals(null, why(gauge));
        assertEquals(256, gauge.get("width").getAsInt());
        assertEquals(144, gauge.get("height").getAsInt());
        assertEquals("Machine", listed("machine").get("name").getAsString());
        // the route: the frame at the width asked, with its capture time; GETs while an agent holds the machine
        long t0 = System.currentTimeMillis();
        Res f = get("/api/cameras/gauge/frame.jpg?w=128");
        assertEquals(200, f.status, f.toString());
        assertEquals("image/jpeg", f.type);
        assertEquals(128, f.image().getWidth());
        assertEquals(72, f.image().getHeight());
        assertTrue(f.atMs != null && f.atMs > t0 && f.atMs <= System.currentTimeMillis(), "X-Frame-At-Ms " + f.atMs);
        assertEquals(320, get("/api/cameras/machine/frame.jpg").image().getWidth(), "no w: the whole frame");
        // after=: the next frame, newer than the one the client has
        Res g = get("/api/cameras/gauge/frame.jpg?w=128&after=" + f.atMs);
        assertEquals(200, g.status, g.toString());
        assertTrue(g.atMs > f.atMs, g.atMs + " > " + f.atMs);
        long ahead = System.currentTimeMillis() + 400;
        Res h = get("/api/cameras/gauge/frame.jpg?after=" + ahead);
        assertEquals(200, h.status, h.toString());
        assertTrue(h.atMs > ahead, h.atMs + " > " + ahead);
        refused(504, "frameWaitMs", get("/api/cameras/gauge/frame.jpg?after=" + (System.currentTimeMillis() + 60_000)));
        refused(400, "after", get("/api/cameras/gauge/frame.jpg?after=soon"));
        // a partial write keeps the other camera; null takes one off the list
        ok(put("{\"usbCameras\":{\"gauge\":null}}"));
        assertEquals(null, listed("gauge"));
        assertNotNull(listed("machine"));
        refused(404, "no gauge camera", get("/api/cameras/gauge/frame.jpg"));
    }

    @Test
    public void offTheSimulatorOnlyByIdDevices() throws Exception {
        stills(); // the controller answers as Marlin: the real machine's rules
        refused(400, "simulated", put("{\"usbCameras\":{\"gauge\":{\"device\":\"file:gauge.jpg\",\"width\":256,"
                + "\"height\":144}}}"));
        refused(400, "/dev/v4l/by-id/", put("{\"usbCameras\":{\"gauge\":{\"device\":\"/dev/video2\",\"width\":1280,"
                + "\"height\":720}}}"));
        refused(400, "MJPG", put("{\"usbCameras\":{\"gauge\":{\"device\":\"" + GAUGE_BY_ID + "\",\"fourcc\":\"YUYV\","
                + "\"width\":1280,\"height\":720}}}"));
        assertEquals(null, listed("gauge"), "nothing refused was saved");
        ok(put("{\"usbCameras\":{\"gauge\":{\"device\":\"" + GAUGE_BY_ID + "\",\"width\":1280,\"height\":720}}}"));
        JsonObject gauge = listed("gauge");
        assertFalse(gauge.get("present").getAsBoolean(), gauge.toString());
        assertEquals("not plugged in: " + GAUGE_BY_ID + " is gone", why(gauge));
        assertEquals(1280, gauge.get("width").getAsInt());
        Res f = get("/api/cameras/gauge/frame.jpg?w=640");
        refused(503, "camera-unavailable", f);
        assertEquals("not plugged in: " + GAUGE_BY_ID + " is gone", f.json().get("why").getAsString());
    }

    @Test
    public void theLoaderRefusesWhatTheWriteRefuses() throws Exception {
        simulated();
        stills();
        ok(put(STILLS));
        assertEquals(200, get("/api/cameras/machine/frame.jpg?w=64").status);
        // as a settings file edited by hand: a /dev/videoN path (it renumbers) is never opened
        DispenseConfig c = svc.getConfig();
        c.usbCameras.gauge.device = "/dev/video2";
        svc.updateConfig(c);
        JsonObject gauge = listed("gauge");
        assertFalse(gauge.get("present").getAsBoolean());
        assertTrue(why(gauge).startsWith("refused: ") && why(gauge).contains("/dev/v4l/by-id/"), why(gauge));
        Res f = get("/api/cameras/gauge/frame.jpg");
        refused(503, "camera-unavailable", f);
        assertEquals(why(gauge), f.json().get("why").getAsString());
        // the stills stop with the simulator: the controller now answers as the real board
        SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:Marlin"));
        JsonObject machine = listed("machine");
        assertFalse(machine.get("present").getAsBoolean());
        assertTrue(why(machine).startsWith("refused: ") && why(machine).contains("simulated"), why(machine));
        refused(503, "simulated", get("/api/cameras/machine/frame.jpg"));
    }

    @Test
    public void topAndBottomSendFrameAtMsAndTakeAfter() throws Exception {
        ImageCamera top = new ImageCamera();
        top.setName("Top");
        top.setLooking(Camera.Looking.Down);
        sim.machine.getDefaultHead().addCamera(top);
        ImageCamera bottom = new ImageCamera();
        bottom.setName("Bottom");
        bottom.setLooking(Camera.Looking.Up);
        sim.machine.addCamera(bottom);
        for (String id : new String[] {"top", "bottom"}) {
            Res f = get("/api/cameras/" + id + "/frame.jpg?w=320");
            assertEquals(200, f.status, f.toString());
            assertNotNull(f.atMs, "X-Frame-At-Ms");
            assertEquals(String.valueOf(f.atMs), f.at, "X-Frame-At kept");
            long ahead = System.currentTimeMillis() + 400;
            Res g = get("/api/cameras/" + id + "/frame.jpg?w=320&after=" + ahead);
            assertEquals(200, g.status, g.toString());
            assertTrue(g.atMs > ahead, g.atMs + " > " + ahead);
            refused(504, "frameWaitMs", get("/api/cameras/" + id + "/frame.jpg?after="
                    + (System.currentTimeMillis() + 60_000)));
        }
    }
}
