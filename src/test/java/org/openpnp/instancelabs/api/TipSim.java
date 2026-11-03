package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.function.Consumer;

import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The SIM machine, the dispense service and the real API for the tip setup tests (PROFILE_CALIBRATION_DESIGN.md §22.8):
 * OpenPnP's GcodeDriver over loopback to the RecordingGcodeServer (no device is opened), an ephemeral 127.0.0.1 port,
 * the bench's places, needle frame and surfaces as ApiServerSimTest sets them, and the 25G straight as Add a tip adds
 * it.
 */
final class TipSim implements AutoCloseable {
    static final String SEED = "ad22tta-b";
    static final String K25 = "25g-straight";
    /** Add a tip's preset "25G straight" (DSKIT1, 0.5 in steel), its recipe from the defaults. */
    static final String ADD_25 = "{\"name\":\"25G straight\",\"tipModel\":\"DSKIT1 25G\",\"gauge\":25,\"idMm\":0.25,"
            + "\"odMm\":0.51,\"form\":\"straight\",\"taper\":\"straight-steel\",\"lengthMm\":12.7,"
            + "\"profileFrom\":\"default\"}";

    final SimMachine sim;
    final DispenseService svc;
    final ApiServer api;
    final HttpClient http;

    TipSim() throws Exception {
        sim = new SimMachine();
        // N1 touches 5.5 everywhere: a run after a home checks Z with one touch
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void close() throws Exception {
        hook(null);
        try {
            api.stop();
            svc.shutdown();
        }
        finally {
            sim.close();
        }
    }

    ApiContext ctx() {
        return api.getContext();
    }

    // ------------------------------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;
        final HttpResponse<byte[]> raw;

        Res(HttpResponse<byte[]> r) {
            raw = r;
            status = r.statusCode();
            body = new String(r.body(), StandardCharsets.UTF_8);
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        JsonArray array() {
            return new JsonParser().parse(body).getAsJsonArray();
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 4000 ? body.substring(0, 4000) + "…" : body);
        }
    }

    Res call(String method, String path, String json, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        if (json != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    /** A body of bytes (a picture), as the browser posts it. */
    Res send(String method, String path, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120)).header("Content-Type", contentType)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    /**
     * The answer to a raw request that declares a body of {@code contentLength} bytes and sends only its first 16 KB
     * (the server answers a declared body over the route's limit before reading it): the status line, the headers and
     * the body.
     */
    String rawAnswer(String path, long contentLength) throws Exception {
        try (java.net.Socket s = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), api.getPort())) {
            s.setSoTimeout(20_000);
            java.io.OutputStream out = s.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + api.getPort() + "\r\nContent-Type: "
                    + "image/jpeg\r\nContent-Length: " + contentLength + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[16 * 1024]);
            out.flush();
            java.io.InputStream in = s.getInputStream();
            StringBuilder head = new StringBuilder();
            int length = 0;
            String line;
            while (!(line = readLine(in)).isEmpty()) {
                head.append(line).append('\n');
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            return head + "\n" + new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }
    }

    private static String readLine(java.io.InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n';) {
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    Res get(String path, String... headers) throws Exception {
        return call("GET", path, null, headers);
    }

    Res post(String path, String json) throws Exception {
        return call("POST", path, json);
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static JsonArray okArray(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.array();
    }

    /** A refusal with its status and code; returns the body. */
    static JsonObject refused(Res r, int status, String code) {
        assertEquals(status, r.status, r.toString());
        JsonObject b = r.json();
        assertEquals(code, str(b, "error"), r.toString());
        return b;
    }

    static String str(JsonObject o, String k) {
        return o == null || o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsString();
    }

    static Long lng(JsonObject o, String k) {
        return o == null || o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsLong();
    }

    /** "null" or the number, for a JSON body. */
    static String js(Long v) {
        return v == null ? "null" : Long.toString(v);
    }

    JsonObject dispense() throws Exception {
        return ok(get("/api/status")).getAsJsonObject("dispense");
    }

    /** The needle token the UI sends with what it measured (status dispense.needle.changedAtMs). */
    Long token() throws Exception {
        return lng(dispense().getAsJsonObject("needle"), "changedAtMs");
    }

    // ------------------------------------------------------------------------------------------
    // The bench
    // ------------------------------------------------------------------------------------------

    /** Places, the mounted needle's frame and offset, the blank and board surfaces, a purge row, the start angle. */
    void bench() throws Exception {
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}"));
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}"));
        frame();
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}"));
        ok(call("PUT", "/api/surfaces/board", "{\"points\":[[98,78,5.5],[130,78,5.5],[98,95,5.5],[130,95,5.5]]}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":88,\"y0Mm\":45,\"pitchXMm\":2.5,\"spots\":16},"
                + "\"cleanPadCheck\":{\"enabled\":false}}"));
        ok(post("/api/screw/start-angle", "{}"));
    }

    /** The mounted needle's frame (N1 at 5.5, offset -2.59) and its offset confirmed, under the needle token now. */
    void frame() throws Exception {
        Long t = token();
        ok(post("/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactZ\":2.91,\"needleChangedAtMs\":" + js(t) + "}"));
        ok(post("/api/needle/offset", "{\"offsetMm\":-2.59,\"needleChangedAtMs\":" + js(t) + "}"));
    }

    /** The 25G straight added (Add a tip, its recipe from the defaults). */
    JsonObject add25() throws Exception {
        Res r = post("/api/tip-setups", ADD_25);
        assertEquals(201, r.status, r.toString());
        return r.json().getAsJsonObject("setup");
    }

    JsonObject mount(String key) throws Exception {
        return ok(post("/api/tip-setups/" + key + "/mount", "{\"why\":\"test\",\"operator\":\"tester\"}"));
    }

    /** A pad type's row in GET /api/pad-types (the mounted setup's composed view). */
    JsonObject typeRow(String key) throws Exception {
        JsonObject l = ok(get("/api/pad-types"));
        for (JsonElement f : l.getAsJsonArray("families")) {
            for (JsonElement t : f.getAsJsonObject().getAsJsonArray("types")) {
                if (key.equals(str(t.getAsJsonObject(), "typeKey"))) {
                    return t.getAsJsonObject();
                }
            }
        }
        throw new AssertionError("no pad type " + key + " in " + l);
    }

    /** A toy board of three 0603 resistors (20 x 10 mm), as ApiServerSimTest writes it. */
    File writeBoard() throws Exception {
        Package pkg = new Package("R_0603_1608Metric");
        Configuration.get().addPackage(pkg);
        Part part = new Part("R0603-TIP-TEST");
        part.setPackage(pkg);
        Configuration.get().addPart(part);
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"tip-test.board.xml\">\n"
                + "<dimensions units=\"Millimeters\" x=\"20.0\" y=\"10.0\" z=\"0.0\" rotation=\"0.0\"/>\n<placements>\n");
        double[][] pos = {{2, 2, 0}, {8, 2, 90}, {14, 6, 0}};
        for (int i = 0; i < pos.length; i++) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"R").append(i + 1)
                    .append("\" part-id=\"R0603-TIP-TEST\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(pos[i][0]).append("\" y=\"")
                    .append(pos[i][1]).append("\" z=\"0.0\" rotation=\"").append(pos[i][2]).append("\"/>\n")
                    .append("<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File f = new File(sim.configDir, "tip-test.board.xml");
        Files.write(f.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return f;
    }

    /** POST /api/plan of the toy board at X 100, Y 80. */
    JsonObject plan(File board) throws Exception {
        return ok(post("/api/plan", "{\"source\":\"file\",\"file\":\"" + board.getAbsolutePath().replace("\\", "\\\\")
                + "\",\"x\":100,\"y\":80,\"z\":5.5,\"rotation\":0}"));
    }

    /** The texts of a plan view's interlock violations. */
    static String violations(JsonObject plan) {
        StringBuilder b = new StringBuilder();
        for (JsonElement v : plan.getAsJsonObject("interlocks").getAsJsonArray("violations")) {
            b.append(v.getAsJsonObject().get("code").getAsString()).append(": ")
                    .append(v.getAsJsonObject().get("message").getAsString()).append("\n");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------
    // The engine's test seam
    // ------------------------------------------------------------------------------------------

    /**
     * DispenseService's test hook (package-private in the engine: set by reflection), called at named points ("switch":
     * a mount holding the service's lock and the busy flag); null removes it.
     */
    static void hook(Consumer<String> h) throws Exception {
        Field f = DispenseService.class.getDeclaredField("testHook");
        f.setAccessible(true);
        f.set(null, h);
    }
}
