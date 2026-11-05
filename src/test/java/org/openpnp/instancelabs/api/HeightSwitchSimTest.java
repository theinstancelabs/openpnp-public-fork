package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.HeightSwitch;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.axis.ReferenceControllerAxis;
import org.openpnp.model.Configuration;
import org.openpnp.model.Length;
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

    /**
     * Review MINOR 9: the simulated PA5 floats until its pull-up is set (the board read a floating 0 after a reset), so
     * the engine's measures here need the pull-up first, as the unit fake checks.
     */
    @Test
    public void theSimulatedPinFloatsUntilItsPullUpIsSet() throws Exception {
        DriverGcodeChannel ch = new DriverGcodeChannel(sim.driver);
        String before = String.join(" ", ch.collectProbe("M43 P5", 5000));
        assertTrue(before.contains("Input  = 0"), "floating: " + before);
        ch.sendProbe("M42 P53 T2", 5000);
        String after = String.join(" ", ch.collectProbe("M43 P5", 5000));
        assertTrue(after.contains("Input  = 1"), "pulled up, not pressed: " + after);
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

    /** Where the mounted needle lands, checked now under N2's head offset (the needle is aimed at the switch by it). */
    void checkedLanding() throws Exception {
        DispenseConfig c = svc.getConfig();
        DispenseConfig.NeedleLanding l = new DispenseConfig.NeedleLanding();
        double[] ho = svc.headOffsetNow();
        l.headOffsetXMm = ho[0];
        l.headOffsetYMm = ho[1];
        l.setAtMs = System.currentTimeMillis();
        c.needleLanding = l;
        svc.updateConfig(c);
    }

    long count(String line) {
        return sim.server.all().stream().filter(line::equals).count();
    }

    void waitFor(java.util.function.BooleanSupplier ok) throws Exception {
        long until = System.currentTimeMillis() + 60000;
        while (!ok.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < until, "timed out: " + sim.server.all());
            Thread.sleep(2);
        }
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
        assertEquals(5, n1.getAsJsonArray("releases").size(), n1.toString());
        // the SIM's switch lets go where it trips (no differential): released at the first 0.25 mm lift
        assertEquals(0.25, n1.get("differentialMm").getAsDouble(), 1e-9, n1.toString());

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
        assertEquals(n1.get("releases"), hs.getAsJsonObject("n1").get("releases"), hs.toString());
        assertEquals(0.25, hs.getAsJsonObject("n1").get("differentialMm").getAsDouble(), 1e-9, hs.toString());
        assertEquals(needle.get("releases"), hs.getAsJsonObject("needle").get("releases"), hs.toString());
        assertEquals(0.25, hs.getAsJsonObject("needle").get("differentialMm").getAsDouble(), 1e-9, hs.toString());
        assertEquals(a + b, hs.get("kSwitch").getAsDouble(), 1e-9);
        assertEquals(N1_TRIP + NEEDLE_TRIP, hs.get("kSwitch").getAsDouble(), 0.05);
        // engine-stamped: the settings editor cannot change them
        call("PUT", "/api/config", "{\"heightSwitch\":{\"n1\":null,\"needle\":null}}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("/api/height-switch/measure"), lastBody);
        // #60 merge review NIT: while the position is unknown (a reset, a lost link) none either, that the reason
        sim.machine.setEnabled(false);
        sim.server.powerCycle();
        sim.machine.setEnabled(true);
        String unknown = svc.positionUnknownWhy();
        assertNotNull(unknown);
        JsonObject reset = heightSwitchStatus();
        assertTrue(reset.get("kSwitch").isJsonNull(), reset.toString());
        assertEquals(unknown, reset.get("kSwitchWhy").getAsString());
        // review NIT: K_switch is of this home's trips only; after a new home it is null, with the reason
        sim.machine.home();
        JsonObject later = heightSwitchStatus();
        assertTrue(later.get("kSwitch").isJsonNull(), later.toString());
        assertEquals("N1 has no accepted trip on the height switch in this home", later.get("kSwitchWhy")
                .getAsString());
        assertTrue(hs.get("kSwitchWhy").isJsonNull(), "both of this home: no reason: " + hs);
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
            if (l.startsWith("G1 Z")) {
                z.add(l);
            }
        }
        assertEquals("G1 Z31.500 F600", z.get(0), "travel height first: " + z);
        // the stepped way down (review MAJOR 4) ends at the start, 1.0 above the trip; nothing went lower before it
        int start = z.indexOf(String.format(java.util.Locale.ROOT, "G1 Z%.3f F300", trip + 1.0));
        assertTrue(start > 0, "1.0 above: " + z);
        for (String l : z.subList(1, start)) {
            assertTrue(Double.parseDouble(l.split(" ")[1].substring(1)) > trip + 1.0, "lower on the way down: " + l);
        }
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

    /**
     * Review MAJOR 1: a line that times out mid-descent ends the measure with N1 lifted to travel Z (Z only) before the
     * engine's recovery, and the route answers 409 with the failure.
     */
    @Test
    public void aTimeoutMidDescentLiftsTheToolAndAnswers409() throws Exception {
        ok("PUT", "/api/config", "{\"commandTimeoutMs\":400}");
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        sim.server.mute.add("G1 Z20.650 F300"); // a coarse step's ok never comes
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("timeout waiting for response to G1 Z20.650 F300"), lastBody);
        List<String> l = moves();
        int at = l.indexOf("G1 Z20.650 F300");
        assertTrue(at >= 0 && at + 1 < l.size(), l.toString());
        assertEquals("G1 Z31.500 F600", l.get(at + 1), "lifted right after the line that timed out: " + l);
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
        assertTrue(heightSwitchStatus().get("n1").isJsonNull());
    }

    /**
     * Review MAJOR 4 through the engine: the needle (trip 43) asked to start at 45 stops on its stepped way down within
     * one coarse step (0.10) of the trip, and the measure is refused.
     */
    @Test
    public void aStartPastTheTripIsRefusedOnTheWayDown() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":45,\"zFloor\":46,\"tipClean\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("already pressed") && lastBody.contains("start higher"), lastBody);
        double deepest = 0;
        for (String l : moves()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)").matcher(l);
            if (m.find()) {
                deepest = Math.max(deepest, Double.parseDouble(m.group(1)));
            }
        }
        assertTrue(deepest <= NEEDLE_TRIP + 0.10 + 1e-9, "at most one coarse step past the trip: " + moves());
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
    }

    /**
     * Review MINOR 7 through the engine: a window outside Z's soft limits (N1 zStart 1, zFloor -1 under a low limit of
     * 0) and a travel Z outside Z's safe zone are refused with a plain reason before anything moves.
     */
    @Test
    public void aWindowOutsideTheSoftLimitsOrATravelZOutsideTheSafeZoneIsRefused() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        for (org.openpnp.spi.Axis a : sim.machine.getAxes()) {
            if (a instanceof ReferenceControllerAxis && "Z".equals(((ReferenceControllerAxis) a).getLetter())) {
                ((ReferenceControllerAxis) a).setSoftLimitLow(new Length(0, LengthUnit.Millimeters));
                ((ReferenceControllerAxis) a).setSoftLimitLowEnabled(true);
            }
        }
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":1,\"zFloor\":-1}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("Z -1.000 lies outside Z's soft limits 0.000..-"), lastBody);
        assertEquals(new ArrayList<String>(), moves(), "nothing moved");
        ok("PUT", "/api/config", "{\"travelSafeZMm\":40}"); // N2 at 40: raw Z 23 (z2 = 63 - raw)
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("travel Z 23.000 lies outside Z's safe zone 26.500..36.500"), lastBody);
        assertEquals(new ArrayList<String>(), moves(), "nothing moved");
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

    /**
     * Review MAJOR 2: the needle's measure switches the valve and then the pump off (as STOP orders them) before its
     * first move, through the engine's gate: the passthrough may have left them on.
     */
    @Test
    public void theNeedlesMeasureSwitchesTheValveAndThePumpOffFirst() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        sim.server.mark();
        HeightSwitch.Result r = svc.measureHeightSwitch("needle", 1, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5, true).get(
                120, TimeUnit.SECONDS);
        assertEquals(1, r.trips.size(), String.valueOf(r.why));
        List<String> l = sim.server.sinceMark();
        int valve = l.indexOf("M107 P3"), pump = l.indexOf("M107 P2"), move = l.indexOf(moves().get(0));
        assertTrue(valve >= 0 && valve < pump && pump < move, l.toString());
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

    /**
     * #60 review (MAJOR, safety): the needle is aimed at the switch by where it lands. Until that is checked (a new or
     * changed needle, N2's head offset changed) the aim may be off by up to 15 mm, and the tip would go down to zFloor
     * beside the plunger or onto the housing: refused, nothing moves.
     */
    @Test
    public void theNeedlePressesTheSwitchOnlyOnceWhereItLandsIsChecked() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        Thread.sleep(5); // the change comes later than the SIM's landing, in ms
        DispenseConfig c = svc.getConfig();
        c.needleChangedAtMs = System.currentTimeMillis(); // a new needle: where it lands is not checked yet
        svc.updateConfig(c);
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":42,\"zFloor\":43.5,\"tipClean\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("aimed at the switch by where it lands")
                && lastBody.contains("where it lands has not been checked") && lastBody.contains("then clean the tip"),
                lastBody);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent, not even the vent");
        assertTrue(ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}")
                .get("accepted").getAsBoolean(), "N1 does not depend on the needle's landing");
        checkedLanding();
        assertTrue(ok("POST", "/api/height-switch/measure",
                "{\"tool\":\"needle\",\"zStart\":42,\"zFloor\":43.5,\"tipClean\":true}").get("accepted")
                .getAsBoolean());
    }

    /**
     * #60 review (MAJOR, safety): a needle change voids the old needle's trip. No default window from it (a longer new
     * tip would go its extra length further down, onto the housing) and no K_switch from it.
     */
    @Test
    public void aNeedleChangeVoidsTheOldNeedlesTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        checkedLanding();
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":42,\"zFloor\":43.5,\"tipClean\":true}");
        assertFalse(heightSwitchStatus().get("kSwitch").isJsonNull());
        Thread.sleep(5); // the change comes later than the trip, in ms
        DispenseConfig c = svc.getConfig();
        c.needleChangedAtMs = System.currentTimeMillis();
        svc.updateConfig(c);
        checkedLanding(); // the new needle's landing is checked: only its trip is missing
        JsonObject hs = heightSwitchStatus();
        assertTrue(hs.get("kSwitch").isJsonNull() && hs.get("needle").isJsonNull(), hs.toString());
        assertTrue(hs.get("kSwitchWhy").getAsString().startsWith("the needle has no accepted trip"), hs.toString());
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"tipClean\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("zStart and zFloor (raw Z) are needed"), lastBody);
        assertEquals(new ArrayList<String>(), moves(), "nothing moved");
    }

    /**
     * #60 re-review MINOR 1: a needle change recorded while the needle's measure waits for its final lift's M400: the
     * old needle's trip must not be recorded after the change.
     */
    @Test
    public void aNeedleChangeDuringTheFinalLiftKeepsNoTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        String lift = "G1 Z31.500 F600";
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), m400 = new CountDownLatch(1);
        try {
            long before = count(lift);
            sim.server.holdOkUntil.put(lift, first);
            Future<HeightSwitch.Result> f = svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5,
                    true);
            waitFor(() -> count(lift) == before + 1);
            sim.server.holdOkUntil.put(lift, second);
            first.countDown();
            waitFor(() -> count(lift) == before + 2); // the final lift
            long m400s = count("M400");
            sim.server.holdOkUntil.remove(lift);
            sim.server.holdOkUntil.put("M400", m400);
            second.countDown();
            waitFor(() -> count("M400") == m400s + 1); // the final lift's M400, its ok held
            svc.recordNeedleChange("the needle came off", "test");
            m400.countDown();
            HeightSwitch.Result r = f.get(60, TimeUnit.SECONDS);
            assertFalse(r.accepted && !r.refused, "a measure of the needle that came off: " + r.why);
            assertNull(HeightSwitch.needleTrip(svc.getConfig()));
        }
        finally {
            first.countDown();
            second.countDown();
            m400.countDown();
        }
    }

    /**
     * #60 re-review MINOR 2: a clock set back after the trip (emulated by a trip stamped an hour ahead): the change's
     * token still comes later than the trip, so the old trip is void; a new trip then counts at once.
     */
    @Test
    public void aClockSetBackDoesNotKeepTheOldNeedlesTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5, true)
                .get(60, TimeUnit.SECONDS).accepted);
        DispenseConfig c = svc.getConfig();
        c.heightSwitch.needle.atMs += 3_600_000;
        svc.updateConfig(c);
        svc.recordNeedleChange("the needle came off", "test");
        assertNull(HeightSwitch.needleTrip(svc.getConfig()), "the old needle's trip");
        checkedLanding();
        c = svc.getConfig();
        c.needleLanding.setAtMs = c.needleChangedAtMs; // checked for the new needle (its token is ahead of the clock)
        svc.updateConfig(c);
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5, true)
                .get(60, TimeUnit.SECONDS).accepted);
        assertNotNull(HeightSwitch.needleTrip(svc.getConfig()), "the new needle's trip counts though the clock is behind");
    }
}
