package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.HeightSwitch;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-94 on the simulated controller: N1 and the needle press the bed's height switch (RecordingGcodeServer's PA5,
 * pressed below N1's trip and above the needle's in raw Z) through the engine; an accepted measure is the tool's trip
 * of this home, and the needle presses it only with a clean tip.
 */
public class HeightSwitchSimTest {
    /** N1 trips at raw Z 20 (it goes down as raw Z falls), the needle at 43 (as raw Z rises; z2 = 63 - raw). */
    static final double N1_TRIP = 20.0, NEEDLE_TRIP = 43.0;

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
        sim.enableAndHome(); // the service sees this home: its time is the trips' home
        sim.server.heightSwitchN1TripZ = N1_TRIP;
        sim.server.heightSwitchNeedleTripZ = NEEDLE_TRIP;
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }

    JsonObject ok(String method, String path, String json) throws Exception {
        JsonObject o = call(method, path, json);
        assertTrue(lastStatus >= 200 && lastStatus < 300, lastStatus + " " + o);
        return o;
    }

    /** The lines since the mark that move an axis. */
    List<String> moves() {
        List<String> out = new ArrayList<>();
        for (String l : sim.server.sinceMark()) {
            if (l.startsWith("G0") || l.startsWith("G1") || l.startsWith("G28")) {
                out.add(l);
            }
        }
        return out;
    }

    static Location mm(Location l) {
        return l.convertToUnits(LengthUnit.Millimeters);
    }

    /** "Down" is read from each tool's mapper: N1 goes down as raw Z falls, the needle (the see-saw) as it rises. */
    @Test
    public void downFollowsEachToolsMapper() throws Exception {
        assertEquals(-1, HeightSwitch.down(OpenPnpRawMapper.forProbeNozzle(sim.machine, sim.n1, "b"), 150, 200, 31.5));
        assertEquals(1, HeightSwitch.down(new OpenPnpRawMapper(sim.machine, sim.n2, "b"), 150, 200, 31.5));
    }

    @Test
    public void theEngineMeasuresN1OverThePlaceAndKeepsItsTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        HeightSwitch.Result r = svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5, false).get(120,
                TimeUnit.SECONDS);
        assertTrue(r.accepted, r.why);
        assertEquals(5, r.trips.size());
        assertEquals(N1_TRIP, r.tripRawZ, 0.025);
        Long home = svc.getHomeTracker().getLastHomeAtMs();
        assertNotNull(home);
        assertEquals(home, r.homeAtMs);
        DispenseConfig.HeightSwitchTrip t = svc.getConfig().heightSwitch.n1;
        assertEquals(r.tripRawZ, t.tripRawZ, 0);
        assertEquals(home, t.homeAtMs);
        assertEquals(5, t.presses);
        assertTrue(DispenseConfig.load(sim.configDir).heightSwitch.n1 != null, "saved");
        // N1 ends over the place at travel height, and OpenPnP knows it
        Location n1 = mm(sim.n1.getLocation());
        assertEquals(150, n1.getX(), 1e-6);
        assertEquals(200, n1.getY(), 1e-6);
        assertEquals(31.5, n1.getZ(), 1e-6);
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
        assertEquals(1, svc.getDb().query("SELECT * FROM events WHERE code = ?", "HEIGHT_SWITCH").size());
        assertFalse(svc.isBusy());
    }

    JsonObject heightSwitchStatus() throws Exception {
        return ok("GET", "/api/status", null).getAsJsonObject("dispense").getAsJsonObject("heightSwitch");
    }

    /**
     * The route: no place, then N1 with its window; the needle only with the clean-tip word (nothing moves without
     * it); the status keeps both trips and K_switch, their sum in the same home.
     */
    @Test
    public void n1AndTheNeedleAreMeasuredAndKSwitchIsTheirSum() throws Exception {
        JsonObject none = heightSwitchStatus();
        assertTrue(none.get("place").isJsonNull() && none.get("n1").isJsonNull() && none.get("needle").isJsonNull()
                && none.get("kSwitch").isJsonNull() && none.get("lastRead").isJsonNull(), none.toString());
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21,\"zFloor\":19.5}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("place is not set") && lastBody.contains("/api/locations/height-switch"),
                lastBody);
        assertEquals(new ArrayList<String>(), moves(), "nothing moved");

        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        JsonObject n1 = ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertTrue(n1.get("accepted").getAsBoolean(), n1.toString());
        assertEquals(N1_TRIP, n1.get("tripRawZ").getAsDouble(), 0.025);
        assertEquals(5, n1.getAsJsonArray("trips").size(), "5 presses by default");

        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":42,\"zFloor\":43.5}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("only with a clean tip: paste must never touch it"), lastBody);
        assertEquals(new ArrayList<String>(), moves(), "nothing moved");
        JsonObject needle = ok("POST", "/api/height-switch/measure",
                "{\"tool\":\"needle\",\"zStart\":42,\"zFloor\":43.5,\"tipClean\":true}");
        assertTrue(needle.get("accepted").getAsBoolean(), needle.toString());
        assertEquals(NEEDLE_TRIP, needle.get("tripRawZ").getAsDouble(), 0.025);

        JsonObject hs = heightSwitchStatus();
        assertEquals(150, hs.getAsJsonObject("place").get("x").getAsDouble(), 1e-9);
        double a = hs.getAsJsonObject("n1").get("tripRawZ").getAsDouble();
        double b = hs.getAsJsonObject("needle").get("tripRawZ").getAsDouble();
        assertEquals(n1.get("tripRawZ").getAsDouble(), a, 1e-9);
        assertEquals(needle.get("tripRawZ").getAsDouble(), b, 1e-9);
        assertEquals(a + b, hs.get("kSwitch").getAsDouble(), 1e-9);
        assertEquals(N1_TRIP + NEEDLE_TRIP, hs.get("kSwitch").getAsDouble(), 0.05);
        // engine-stamped: the settings editor cannot change them
        call("PUT", "/api/config", "{\"heightSwitch\":{\"n1\":null,\"needle\":null}}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("/api/height-switch/measure"), lastBody);
    }

    /** Without zStart and zFloor the window is 1.0 above to 0.5 past the tool's accepted trip of this home. */
    @Test
    public void aSecondMeasureStartsFromTheStoredTrip() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("zStart and zFloor (raw Z) are needed"), lastBody);
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        double trip = heightSwitchStatus().getAsJsonObject("n1").get("tripRawZ").getAsDouble();
        sim.server.mark();
        JsonObject again = ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"presses\":3}");
        assertEquals(3, again.getAsJsonArray("trips").size(), again.toString());
        assertFalse(again.get("accepted").getAsBoolean(), "3 presses: a mean, not a recorded trip");
        List<String> z = new ArrayList<>();
        for (String l : moves()) {
            if (l.startsWith("G1 Z") && l.endsWith(" F600")) {
                z.add(l);
            }
        }
        assertEquals("G1 Z31.500 F600", z.get(0), "travel height first: " + z);
        assertEquals(String.format(java.util.Locale.ROOT, "G1 Z%.3f F600", trip + 1.0), z.get(1), "1.0 above: " + z);
        assertEquals(trip, heightSwitchStatus().getAsJsonObject("n1").get("tripRawZ").getAsDouble(), 0,
                "a measure that is not accepted keeps the trip");
    }

    @Test
    public void pressedAtTheStartIsRefusedAndTheToolEndsAtTravelHeight() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        sim.server.heightSwitchN1TripZ = 21.5; // higher than the window says
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21,\"zFloor\":19.5}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("already pressed at the start") && lastBody.contains("start higher"), lastBody);
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
        assertEquals(31.5, mm(sim.n1.getLocation()).getZ(), 1e-6, "and OpenPnP knows it");
        assertTrue(heightSwitchStatus().get("n1").isJsonNull());
    }

    @Test
    public void aBadToolOrPressesIs400AndTheRoutesAreTheLockHolders() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        for (String body : new String[] {"{\"tool\":\"n2\"}", "{}", "{\"tool\":\"n1\",\"presses\":0}",
                "{\"tool\":\"n1\",\"presses\":21}", "{\"tool\":\"n1\",\"presses\":2.5}"}) {
            call("POST", "/api/height-switch/measure", body);
            assertEquals(400, lastStatus, body + " " + lastBody);
        }
        assertEquals("guarded", LockEndpoints.kindOf("POST", "/api/height-switch/measure"));
        assertEquals("guarded", LockEndpoints.kindOf("POST", "/api/height-switch/read"));
        ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"dots\"}");
        sim.server.mark();
        call("POST", "/api/height-switch/read", null);
        assertEquals(409, lastStatus, lastBody);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
    }

    /** A read sets the pull-up and reads PA5 now; the status keeps it and never sends G-code itself. */
    @Test
    public void aReadIsTheLevelNowAndTheStatusKeepsIt() throws Exception {
        JsonObject r = ok("POST", "/api/height-switch/read", null);
        assertEquals(1, r.get("level").getAsInt(), r.toString());
        assertTrue(sim.server.all().containsAll(java.util.Arrays.asList("M42 P53 T2", "M43 P5")));
        sim.server.heightSwitchIdle = 0;
        ok("POST", "/api/height-switch/read", null);
        sim.server.mark();
        JsonObject last = heightSwitchStatus().getAsJsonObject("lastRead");
        assertEquals(0, last.get("level").getAsInt(), last.toString());
        assertTrue(last.get("atMs").getAsLong() > 0);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "the status sends nothing");
    }

    /** The needle without the clean-tip word: refused before anything is sent (paste must never touch the switch). */
    @Test
    public void theEngineRefusesTheNeedleWithoutACleanTip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        sim.server.mark();
        HeightSwitch.Result r = svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5, false).get(
                120, TimeUnit.SECONDS);
        assertTrue(r.refused && r.why.contains("only with a clean tip: paste must never touch it"), r.why);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
    }
}
