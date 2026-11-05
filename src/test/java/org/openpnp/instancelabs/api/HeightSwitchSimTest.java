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
