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
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.DriverGcodeChannel;
import org.openpnp.instancelabs.dispense.FromBelowScene;
import org.openpnp.instancelabs.dispense.HeightSwitch;
import org.openpnp.instancelabs.dispense.NeedleEndMeasure;
import org.openpnp.instancelabs.dispense.OpenPnpRawMapper;
import org.openpnp.instancelabs.dispense.PhotoStore;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.dispense.TipCamera;
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
 * of this home. The needle presses only after the engine measured its end from below in the same task (the bottom camera
 * is a drawn one, {@link FromBelowScene}, following the simulated controller's raw position: the SIM's own bottom camera
 * does not draw the end yet: INF-16), aimed by that end, in the window the engine derives.
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
    /** The bottom camera: a clean straight tip 0.2 mm off where the tip setup puts it, K as the SIM's switch says. */
    FromBelowScene scene;
    double[] camAt;

    /** The drawn bottom camera at {@link #camAt}, the head where the simulated controller has it. */
    final class SceneCamera implements TipCamera {
        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public double[] locationMm() {
            return camAt.clone();
        }

        @Override
        public DotImager.Frame capture() {
            return new DotImager.Frame(scene.render(sim.server.position('X'), sim.server.position('Y'),
                    sim.server.position('Z')), 0.0667, 0.0656, camAt[0], camAt[1]);
        }
    }

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        svc = DispenseService.start(Configuration.get());
        sim.enableAndHome(); // the service sees this home: its time is the trips' home
        sim.server.heightSwitchN1TripZ = N1_TRIP;
        sim.server.heightSwitchNeedleTripZ = NEEDLE_TRIP;
        DispenseConfig c = svc.getConfig();
        c.heightSwitchMaxPastTripMm = 0.5; // Process's first value (BR-94: the lever's differential x overtravel)
        // the 25G's limits for the SIM's tip
        c.needleEndMeasure.clean.put(c.tip.key, c.needleEndMeasure.clean.get("plastic-25g-taper"));
        svc.updateConfig(c);
        scene = new FromBelowScene(1);
        scene.k = N1_TRIP + NEEDLE_TRIP; // the SIM's see-saw
        // its K is 2 mm under the machine's (~65): the camera 2 mm nearer in raw Z keeps the needle as far from it at
        // each raw Z as on the machine (k ~15.6 at the clean check's raw 40.4, inside tipclean's 14.0-16.3)
        scene.cRaw -= 65.0 - scene.k;
        double[] ho = svc.headOffsetNow(), l = svc.landingInEffect();
        scene.endDx = ho[0] + l[0] + c.needleXyTrim.dxMm + 0.2;
        scene.endDy = ho[1] + l[1] + c.needleXyTrim.dyMm - 0.1;
        camAt = new double[] {scene.camX, scene.camY, 25.2};
        svc.setTipCamera(new SceneCamera());
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
        HeightSwitch.Result r = svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120,
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

        // no window, no clean-tip word (ignored if sent): the engine measures the end, derives the window, presses
        JsonObject needle = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"tipClean\":false}");
        assertTrue(needle.get("accepted").getAsBoolean(), needle.toString());
        assertEquals(NEEDLE_TRIP, needle.get("tripRawZ").getAsDouble(), 0.025);
        JsonObject w = needle.getAsJsonObject("window");
        assertEquals("kParallax", w.get("from").getAsString(), w.toString());
        double expected = w.get("expectedRawZ").getAsDouble();
        assertEquals(NEEDLE_TRIP, expected, 0.1, "kParallax − N1's trip");
        assertEquals(expected - 1, w.get("zStart").getAsDouble(), 1e-9);
        assertEquals(expected + 0.5, w.get("zFloor").getAsDouble(), 1e-9, "± 1 mm, the floor capped 0.5 past");
        assertTrue(needle.get("inUse").getAsBoolean() && needle.get("forReview").getAsBoolean(), needle.toString());
        DispenseConfig cfg = svc.getConfig();
        assertEquals(cfg.tip.key, needle.get("setupKey").getAsString());
        assertEquals(cfg.tip.name, needle.get("tipName").getAsString());
        assertEquals(cfg.needleChangedAtMs == null, needle.get("needleSinceMs").isJsonNull());
        assertTrue(needle.getAsJsonObject("needleEnd").get("kParallax").getAsDouble() > 0, needle.toString());

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
        assertEquals(a + b, hs.getAsJsonObject("needle").get("kSwitch").getAsDouble(), 1e-9, "kept with the trip");
        assertEquals("trip", hs.getAsJsonObject("needleWindow").get("from").getAsString(), hs.toString());
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
        assertEquals("N1 has no accepted trip on the height switch in this home since its place was set", later.get(
                "kSwitchWhy").getAsString());
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
     * Review MAJOR 4 through the engine: the needle (trip 43) asked to start at 43.3 stops on its stepped way down within
     * one coarse step (0.10) of the trip, and the measure is refused.
     */
    @Test
    public void aStartPastTheTripIsRefusedOnTheWayDown() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":43.3,\"zFloor\":43.5}");
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
     * first move (its end from below, the first thing it does), through the engine's gate: the passthrough may have left
     * them on.
     */
    @Test
    public void theNeedlesMeasureSwitchesTheValveAndThePumpOffFirst() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertTrue(svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120, TimeUnit.SECONDS).accepted);
        sim.server.mark();
        HeightSwitch.Result r = svc.measureHeightSwitch("needle", 1, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5).get(
                120, TimeUnit.SECONDS);
        assertEquals(1, r.trips.size(), String.valueOf(r.why));
        List<String> l = sim.server.sinceMark();
        int valve = l.indexOf("M107 P3"), pump = l.indexOf("M107 P2"), move = l.indexOf(moves().get(0));
        assertTrue(valve >= 0 && valve < pump && pump < move, l.toString());
    }

    /**
     * BR-94 needle aim: how far past its expected trip the needle may press the switch is Process's setting, with no
     * default: without it the needle's measure is refused before anything is sent. PUT /api/config sets it (bounded).
     */
    @Test
    public void theNeedleWaitsForProcessToSayHowFarPastTheTripTheSwitchMayBePressed() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        ok("PUT", "/api/config", "{\"heightSwitchMaxPastTripMm\":null}");
        assertEquals(HeightSwitch.NO_MAX_PAST_TRIP, heightSwitchStatus().get("needleWindowWhy").getAsString());
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("Process sets how far past the trip the switch may be pressed"), lastBody);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
        for (String bad : new String[] {"0", "2.5"}) {
            call("PUT", "/api/config", "{\"heightSwitchMaxPastTripMm\":" + bad + "}");
            assertEquals(400, lastStatus, bad + " " + lastBody);
        }
        ok("PUT", "/api/config", "{\"heightSwitchMaxPastTripMm\":0.5}");
        assertTrue(ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}").get("accepted").getAsBoolean());
    }

    /**
     * BR-94 needle aim: a later needle measure expects its trip at its K_switch − N1's trip now; its descent stops
     * heightSwitchMaxPastTripMm past that whatever its floor says, and no trip by then is refused, the needle raised.
     */
    @Test
    public void theNeedlesDescentStopsMaxPastTripPastItsExpectedTrip() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        // its trip lies on its window's step grid (within a fine step of the switch's 43): K_switch − N1's trip is it
        double trip = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}").get("tripRawZ").getAsDouble();
        sim.server.heightSwitchNeedleTripZ = 50.0; // the tip misses the lever now
        sim.server.mark();
        JsonObject r = call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"zStart\":42.5,"
                + "\"zFloor\":44.5}");
        assertEquals(409, lastStatus, lastBody);
        assertEquals(trip, r.getAsJsonObject("result").getAsJsonObject("window").get("expectedRawZ").getAsDouble(),
                1e-9, "N1's trip unchanged: the needle expects its last trip");
        double cap = trip + 0.5;
        assertTrue(lastBody.contains(String.format(java.util.Locale.ROOT, "no trip by Z %.3f", cap)), lastBody);
        double deepest = 0;
        for (String l : moves()) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bZ(-?\\d+(?:\\.\\d+)?)").matcher(l);
            if (m.find()) {
                deepest = Math.max(deepest, Double.parseDouble(m.group(1)));
            }
        }
        assertEquals(cap, deepest, 1e-9, "down to the cap, never past it: " + moves());
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
    }

    /**
     * The engine's own check replaces the clean-tip word (sent or not: ignored): paste on the end is refused as such,
     * with its code, what it offers and its picture; nothing is pressed (no read of the switch). A clean verdict on
     * record from a minute before is history, not a pass (review MINOR 6): the press checks again. The picture is served
     * by its id, also while another client holds the machine (a GET is never locked).
     */
    @Test
    public void pasteOnTheEndIsRefusedByTheCameraNotByAWord() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertTrue(ok("POST", "/api/needle/end/measure", null).getAsJsonObject("end").getAsJsonObject("clean")
                .get("ok").getAsBoolean(), "a clean verdict on record");
        scene.paste = true;
        sim.server.mark();
        JsonObject r = call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\",\"tipClean\":true}");
        assertEquals(409, lastStatus, lastBody);
        assertEquals(NeedleEndMeasure.PASTE, r.get("error").getAsString(), lastBody);
        assertEquals(NeedleEndMeasure.WIPE_THEN_MEASURE_AGAIN, r.get("next").getAsString());
        assertTrue(r.get("reason").getAsString().startsWith("paste on the end: spread"), lastBody);
        long photo = r.get("photoId").getAsLong();
        assertEquals(photo, r.getAsJsonObject("result").get("photoId").getAsLong());
        assertFalse(sim.server.sinceMark().contains("M43 P5"), "nothing pressed: " + sim.server.sinceMark());
        assertEquals(31.5, sim.server.position('Z'), 1e-9);
        assertTrue(heightSwitchStatus().get("needle").isJsonNull());
        ok("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"dots\"}");
        HttpResponse<byte[]> jpg = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort()
                + "/api/photos/" + photo + ".jpg")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, jpg.statusCode(), "served while another client holds the machine");
        assertEquals("image/jpeg", jpg.headers().firstValue("Content-Type").orElse(""));
    }

    /**
     * #60 review (MAJOR, safety): a needle change voids the old needle's trip and its end from below: no K_switch, no
     * window from them. The new needle's first press needs no checked landing (#60's stopgap is gone): it measures its
     * end from below and presses in the window K by parallax gives.
     */
    @Test
    public void aNeedleChangeVoidsTheOldNeedlesTripAndEnd() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertFalse(heightSwitchStatus().get("kSwitch").isJsonNull());
        assertFalse(dispenseStatus().get("needleEnd").isJsonNull());
        Thread.sleep(5); // the change comes later than the trip, in ms
        DispenseConfig c = svc.getConfig();
        c.needleChangedAtMs = System.currentTimeMillis(); // a new needle: where it lands is not checked either
        svc.updateConfig(c);
        assertNotNull(svc.needleXyProblem(), "its landing is not checked");
        JsonObject hs = heightSwitchStatus();
        assertTrue(hs.get("kSwitch").isJsonNull() && hs.get("needle").isJsonNull(), hs.toString());
        // #78's reason: its trip is another needle's (merged with the aim's place rule, which names the other one)
        assertEquals("the needle has no accepted trip on the height switch since it was put on", hs.get("kSwitchWhy")
                .getAsString(), hs.toString());
        assertTrue(dispenseStatus().get("needleEnd").isJsonNull(), "the old needle's end is void");
        assertTrue(hs.get("needleWindow").isJsonNull(), hs.toString());
        assertTrue(hs.get("needleWindowWhy").getAsString().contains("its end measured from below"), hs.toString());
        JsonObject first = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertTrue(first.get("accepted").getAsBoolean(), first.toString());
        assertEquals("kParallax", first.getAsJsonObject("window").get("from").getAsString());
        long token = svc.getConfig().needleChangedAtMs;
        assertEquals(token, first.get("needleSinceMs").getAsLong());
        assertEquals(token, dispenseStatus().getAsJsonObject("needleEnd").get("needleSinceMs").getAsLong());
    }

    JsonObject dispenseStatus() throws Exception {
        return ok("GET", "/api/status", null).getAsJsonObject("dispense");
    }

    /**
     * POST /api/needle/end/measure: the end at each height and its tilt, K by parallax as the switch says, the clean
     * check, a picture per height, the camera's calibration and the needle it is of; recorded (engine-stamped) and shown
     * in the status; void under another camera calibration. Its progress is the run's phases, its events carry the run.
     * Paste on the end is refused the same way as in a press, with its picture.
     */
    @Test
    public void theNeedlesEndFromBelowIsRecordedAndShown() throws Exception {
        svc.getPhotoStore().setUsableBytes(() -> PhotoStore.RAW_MIN_FREE_BYTES); // at the floor: kept
        assertEquals("guarded", LockEndpoints.kindOf("POST", "/api/needle/end/measure"));
        assertTrue(dispenseStatus().get("needleEnd").isJsonNull());
        JsonObject r = ok("POST", "/api/needle/end/measure", null);
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        JsonObject e = r.getAsJsonObject("end");
        assertEquals(N1_TRIP + NEEDLE_TRIP, e.get("kParallax").getAsDouble(), 0.1);
        assertEquals(4, e.getAsJsonArray("heights").size());
        for (com.google.gson.JsonElement h : e.getAsJsonArray("heights")) {
            double z = h.getAsJsonObject().get("rawZ").getAsDouble();
            assertEquals(scene.end(z)[0], h.getAsJsonObject().get("dxMm").getAsDouble(), 0.02, "dx at " + z);
            assertEquals(scene.end(z)[1], h.getAsJsonObject().get("dyMm").getAsDouble(), 0.02, "dy at " + z);
        }
        assertEquals(scene.tiltX, e.get("tiltXMmPerRawMm").getAsDouble(), 0.005);
        assertTrue(e.getAsJsonObject("clean").get("ok").getAsBoolean(), e.toString());
        assertEquals(8, e.getAsJsonArray("photoIds").size());
        assertEquals(e.getAsJsonArray("photoIds").get(7), e.getAsJsonObject("clean").get("photoId"), "the clean "
                + "check's frame, the last");
        assertEquals(scene.camX, e.getAsJsonObject("cameraCalibration").get("x").getAsDouble(), 1e-9);
        assertTrue(e.get("forReview").getAsBoolean());
        String run = r.get("runId").getAsString();
        assertEquals(run, e.get("runId").getAsString());
        // review MINOR 4: every frame as taken, in order, kept at the store's best quality for Process's tools
        assertEquals(43, e.getAsJsonArray("framePhotoIds").size());
        long raw = e.getAsJsonObject("clean").get("framePhotoId").getAsLong();
        assertEquals(e.getAsJsonArray("framePhotoIds").get(42).getAsLong(), raw);
        org.openpnp.instancelabs.dispense.Photo kept = svc.getDb().photo(raw);
        org.openpnp.instancelabs.dispense.Photo drawn = svc.getDb().photo(e.getAsJsonObject("clean").get("photoId")
                .getAsLong());
        assertTrue(kept.caption.startsWith("As taken: the clean check's frame"), kept.caption);
        assertEquals(run, kept.jobId);
        assertTrue(kept.bytes > 2 * drawn.bytes, "the best quality: " + kept.bytes + " vs " + drawn.bytes);
        JsonObject shown = dispenseStatus().getAsJsonObject("needleEnd");
        assertEquals(e.get("kParallax"), shown.get("kParallax"));
        JsonObject status = ok("GET", "/api/status", null).getAsJsonObject("run");
        assertEquals(run, status.get("runId").getAsString());
        assertEquals("needle-end", status.get("kind").getAsString());
        assertTrue(status.getAsJsonArray("phases").toString().contains("needle end: N1 at raw Z 19.000 (1 of 3)"),
                status.toString());
        assertEquals(1, svc.getDb().query("SELECT * FROM events WHERE code = ? AND run_uuid = ?", "NEEDLE_END", run)
                .size(), "its event carries the run");
        call("PUT", "/api/config", "{\"needleEnd\":null}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("/api/needle/end/measure"), lastBody);
        camAt[0] += 0.1; // a new calibration of the bottom camera
        assertTrue(dispenseStatus().get("needleEnd").isJsonNull(), "void under another camera calibration");
        camAt[0] -= 0.1;
        assertFalse(dispenseStatus().get("needleEnd").isJsonNull());
        scene.paste = true;
        JsonObject p = call("POST", "/api/needle/end/measure", null);
        assertEquals(409, lastStatus, lastBody);
        assertEquals(NeedleEndMeasure.PASTE, p.get("error").getAsString());
        assertEquals("spread", p.getAsJsonObject("result").getAsJsonObject("clean").get("failed").getAsString());
        assertEquals(p.get("photoId"), p.getAsJsonObject("result").getAsJsonObject("clean").get("photoId"));
        assertEquals(e.get("kParallax"), dispenseStatus().getAsJsonObject("needleEnd").get("kParallax"),
                "a refusal keeps the end on record");
    }

    /**
     * Re-review MINOR 2: the frames as taken (some 515 kB each on the machine, 22 MB a measure) are kept only while the
     * photo folder's disk has at least 5 GiB usable. Under that the end is still measured and recorded, its frames' ids
     * null; its drawn pictures are kept.
     */
    @Test
    public void theFramesAsTakenAreNotKeptUnder5GiBFree() throws Exception {
        svc.getPhotoStore().setUsableBytes(() -> PhotoStore.RAW_MIN_FREE_BYTES - 1);
        int photos = svc.getDb().query("SELECT id FROM photos").size();
        JsonObject r = ok("POST", "/api/needle/end/measure", null);
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        JsonObject e = r.getAsJsonObject("end");
        assertEquals(43, e.getAsJsonArray("framePhotoIds").size());
        for (com.google.gson.JsonElement id : e.getAsJsonArray("framePhotoIds")) {
            assertTrue(id.isJsonNull(), e.getAsJsonArray("framePhotoIds").toString());
        }
        assertTrue(e.getAsJsonObject("clean").get("framePhotoId").isJsonNull());
        assertEquals(photos + 8, svc.getDb().query("SELECT id FROM photos").size(), "its 8 drawn pictures only");
        assertFalse(dispenseStatus().get("needleEnd").isJsonNull(), "recorded");
    }

    /**
     * The needle's press measures its end in its own task right before the press: every frame is taken before the
     * switch's first read, one task, aimed by the end at the press height (the head at the place minus the end there).
     * Its progress names the task: the end's heights, then the presses.
     */
    @Test
    public void thePressMeasuresTheEndFirstInTheSameTaskAndAimsByIt() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        int runs = svc.getDb().query("SELECT * FROM events WHERE code = ?", "RUN_END").size();
        sim.server.mark();
        JsonObject r = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertTrue(r.get("accepted").getAsBoolean(), r.toString());
        assertEquals(runs + 1, svc.getDb().query("SELECT * FROM events WHERE code = ?", "RUN_END").size(), "one task");
        List<String> l = sim.server.sinceMark();
        int lastFrame = l.lastIndexOf("G4 P300"), firstRead = l.indexOf("M43 P5");
        assertTrue(lastFrame > 0 && firstRead > lastFrame, "the end first, then the press: " + lastFrame + " "
                + firstRead);
        JsonObject aim = r.getAsJsonObject("aim");
        double z = aim.get("atRawZ").getAsDouble();
        assertEquals(r.getAsJsonObject("window").get("expectedRawZ").getAsDouble(), z, 1e-9);
        assertEquals(scene.end(z)[0], aim.get("endDxMm").getAsDouble(), 0.02, "the end extrapolated to the press");
        assertEquals(150 - aim.get("endDxMm").getAsDouble(), aim.get("rawX").getAsDouble(), 1e-3, "the head at the "
                + "place minus the end");
        assertEquals(200 - aim.get("endDyMm").getAsDouble(), aim.get("rawY").getAsDouble(), 1e-3);
        int press = l.indexOf(String.format(java.util.Locale.ROOT, "G1 X%.3f Y%.3f F6000", aim.get("rawX")
                .getAsDouble(), aim.get("rawY").getAsDouble()));
        assertTrue(press > 0, "pressed there: " + l);
        assertEquals(String.format(java.util.Locale.ROOT, "G1 X%.3f Y%.3f F6000", aim.get("rawX").getAsDouble() - 0.5,
                aim.get("rawY").getAsDouble() - 0.5), l.get(press - 1), "approached from (-0.5, -0.5), as the frames");
        JsonObject run = ok("GET", "/api/status", null).getAsJsonObject("run");
        String phases = run.getAsJsonArray("phases").toString();
        assertTrue(phases.contains("needle end: the needle at raw Z 40.400 (3 of 4)") && phases.contains(
                "height switch: needle press 5 of 5"), phases);
        assertEquals("height-switch", run.get("kind").getAsString());
        assertEquals(r.getAsJsonObject("needleEnd").get("runId").getAsString(), run.get("runId").getAsString());
    }

    /**
     * Review MAJOR 1: the end measure and the press need a fresh passing clean check whatever the settings file says. A
     * file whose needle heights miss the clean check's (needleRawZ [36, 39, 42], cleanRawZ 40.4: PUT /api/config refuses
     * it, a hand edit or another jar's file does not) is loaded as it is; both routes are refused before anything is
     * sent (the end measure moved the machine before, and failed only when it recorded the end).
     */
    @Test
    public void aSettingsFileWithoutTheCleanHeightIsRefusedBeforeAnythingIsSent() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}"); // while the file is valid
        com.google.gson.JsonArray z = new com.google.gson.JsonArray();
        for (double v : new double[] {36, 39, 42}) {
            z.add(new com.google.gson.JsonPrimitive(v));
        }
        restartOnFile(o -> o.getAsJsonObject("needleEndMeasure").add("needleRawZ", z));
        assertEquals(java.util.Arrays.asList(36.0, 39.0, 42.0), svc.getConfig().needleEndMeasure.needleRawZ);
        for (String[] route : new String[][] {{"/api/needle/end/measure", null}, {"/api/height-switch/measure",
            "{\"tool\":\"needle\"}"}}) {
            sim.server.mark();
            call("POST", route[0], route[1]);
            assertEquals(409, lastStatus, lastBody);
            assertTrue(lastBody.contains("cleanRawZ must be one of needleRawZ"), lastBody);
            assertEquals(new ArrayList<String>(), sim.server.sinceMark(), route[0] + ": nothing sent");
        }
        assertTrue(heightSwitchStatus().get("needle").isJsonNull());
    }

    /**
     * Re-review MINOR 3: a settings file is loaded as it is (PUT /api/config bounds what it takes, a hand edit does
     * not) and the press takes its window, cap and approach from it. A cap past the trip of 5 mm (a window 1.0 mm past
     * the trip, an override's cap lifted) or an approach of 50 mm (N1 came from 50 mm away at travel Z, a point the
     * soft limits are not checked at, and pressed; only its record refused the file) refuses either tool's press with
     * the file's problem, before anything is sent.
     */
    @Test
    public void aSettingsFileThatDoesNotValidateRefusesThePressBeforeAnythingIsSent() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        restartOnFile(o -> o.addProperty("heightSwitchMaxPastTripMm", 5));
        assertEquals(Double.valueOf(5), svc.getConfig().heightSwitchMaxPastTripMm);
        pressesRefusedBeforeAnythingIsSent("heightSwitchMaxPastTripMm must be above 0 and at most 2.0 mm");
        restartOnFile(o -> {
            o.addProperty("heightSwitchMaxPastTripMm", 0.5);
            o.getAsJsonObject("needleEndMeasure").addProperty("approachMm", 50);
        });
        assertEquals(50, svc.getConfig().needleEndMeasure.approachMm, 1e-9);
        pressesRefusedBeforeAnythingIsSent("needleEndMeasure.approachMm must be above 0 and at most 1 mm");
    }

    /** N1's press with its window, then the needle's: each 409 naming {@code why}, nothing sent. */
    void pressesRefusedBeforeAnythingIsSent(String why) throws Exception {
        for (String press : new String[] {"{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}",
            "{\"tool\":\"needle\"}"}) {
            sim.server.mark();
            call("POST", "/api/height-switch/measure", press);
            assertEquals(new ArrayList<String>(), sim.server.sinceMark(), press + ": nothing sent");
            assertEquals(409, lastStatus, lastBody);
            assertTrue(lastBody.contains("the settings file does not validate: ") && lastBody.contains(why), lastBody);
        }
    }

    /** The settings file edited as by hand (PUT /api/config would refuse it), then the engine started on it, homed. */
    void restartOnFile(java.util.function.Consumer<JsonObject> edit) throws Exception {
        java.io.File f = DispenseConfig.fileFor(sim.configDir);
        JsonObject o = new JsonParser().parse(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        edit.accept(o);
        java.nio.file.Files.write(f.toPath(), o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        svc = DispenseService.start(Configuration.get()); // the engine's start reads the file as it is
        svc.setTipCamera(new SceneCamera());
        sim.machine.home();
    }

    /**
     * Review MINOR 1: PUT /api/config refuses a tip kind's clean limits that leave one out or name one that is no limit
     * (400, naming them): never the 25G's in their place.
     */
    @Test
    public void cleanLimitsThatAreNotWholeAre400() throws Exception {
        JsonObject g25 = ok("GET", "/api/config", null).getAsJsonObject("needleEndMeasure").getAsJsonObject("clean")
                .getAsJsonObject("plastic-25g-taper");
        call("PUT", "/api/config", "{\"needleEndMeasure\":{\"clean\":{\"plastic-25g-taper\":" + g25
                + ",\"steel-27g\":{\"spreadMax\":0.41}}}}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("needleEndMeasure.clean['steel-27g']: no rimMin"), lastBody);
        g25.add("spredMax", g25.remove("spreadMax"));
        call("PUT", "/api/config", "{\"needleEndMeasure\":{\"clean\":{\"plastic-25g-taper\":" + g25 + "}}}");
        assertEquals(400, lastStatus, lastBody);
        assertTrue(lastBody.contains("'spredMax' is no limit"), lastBody);
        assertEquals(0.35, ok("GET", "/api/config", null).getAsJsonObject("needleEndMeasure").getAsJsonObject("clean")
                .getAsJsonObject("plastic-25g-taper").get("spreadMax").getAsDouble(), 1e-9, "nothing changed");
    }

    /**
     * Review MINOR 3, Risk 3 and Process 11-05 04:3x: a second press in the same home takes its window from its own
     * trip, and measures K by parallax (N1's frames) against its K_switch; a press in another home takes it from that
     * K_switch − N1's trip in this home, ± 1 mm (capped 0.5 past), without N1's frames (K by parallax is not needed).
     */
    @Test
    public void laterWindowsComeFromTheNeedlesOwnTripOrItsKSwitch() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        double trip = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}").get("tripRawZ").getAsDouble();
        double kSwitch = heightSwitchStatus().get("kSwitch").getAsDouble();
        JsonObject same = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertEquals("trip", same.getAsJsonObject("window").get("from").getAsString(), same.toString());
        assertEquals(trip, same.getAsJsonObject("window").get("expectedRawZ").getAsDouble(), 1e-3);
        assertEquals(kSwitch, same.getAsJsonObject("needleEnd").get("kParallax").getAsDouble(), 0.5,
                "measured against its K_switch");
        sim.machine.home(); // another home
        double n1 = ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}")
                .get("tripRawZ").getAsDouble();
        kSwitch = svc.getConfig().heightSwitch.needle.kSwitch;
        JsonObject r = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertTrue(r.get("accepted").getAsBoolean(), r.toString());
        JsonObject w = r.getAsJsonObject("window");
        assertEquals("kSwitch", w.get("from").getAsString(), w.toString());
        double expected = w.get("expectedRawZ").getAsDouble();
        assertEquals(kSwitch - n1, expected, 1e-3);
        assertEquals(expected - 1, w.get("zStart").getAsDouble(), 1e-9);
        assertEquals(expected + 0.5, w.get("zFloor").getAsDouble(), 1e-9, "1 mm past, capped 0.5 past");
        JsonObject end = r.getAsJsonObject("needleEnd");
        assertTrue(end.get("kParallax").isJsonNull() && end.getAsJsonArray("n1").size() == 0, end.toString());
        String phases = ok("GET", "/api/status", null).getAsJsonObject("run").getAsJsonArray("phases").toString();
        assertFalse(phases.contains("needle end: N1 at raw Z"), phases);
    }

    /**
     * Re-review MINOR 1: a trip counts only after the switch's place was last set (another point of the lever trips at
     * another height: 3 mm along it some 0.5 mm, 11-04). The place set again after both trips: the needle waits for N1
     * to press at the new place (N1 needs its window again), nothing sent; then its window comes from its K_switch (of
     * any place) − N1's new trip, never from its own trip at the old place.
     */
    @Test
    public void aTripCountsOnlyAfterTheSwitchsPlaceWasSet() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertTrue(ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}").get("accepted").getAsBoolean());
        double kSwitch = svc.getConfig().heightSwitch.needle.kSwitch;
        ok("PUT", "/api/locations/height-switch", "{\"x\":153,\"y\":200,\"z\":0}"); // 3 mm along the lever
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("press N1 on the switch first"), lastBody);
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("zStart and zFloor (raw Z) are needed"), lastBody);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
        JsonObject hs = heightSwitchStatus();
        assertEquals(HeightSwitch.PRESS_N1_FIRST, hs.get("needleWindowWhy").getAsString());
        assertTrue(hs.get("kSwitch").isJsonNull(), hs.toString());
        double n1 = ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}")
                .get("tripRawZ").getAsDouble();
        JsonObject moved = heightSwitchStatus();
        assertTrue(moved.get("kSwitch").isJsonNull(), "the needle's trip is of the old place");
        // of the needle now on, so the place's reason, not #78's "since it was put on"
        assertEquals("the needle has no accepted trip on the height switch in this home since its place was set",
                moved.get("kSwitchWhy").getAsString(), moved.toString());
        JsonObject r = ok("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertTrue(r.get("accepted").getAsBoolean(), r.toString());
        JsonObject w = r.getAsJsonObject("window");
        assertEquals("kSwitch", w.get("from").getAsString(), w.toString());
        assertEquals(kSwitch - n1, w.get("expectedRawZ").getAsDouble(), 1e-3);
        assertEquals("trip", heightSwitchStatus().getAsJsonObject("needleWindow").get("from").getAsString(),
                "its trip at the new place counts");
    }

    /**
     * Re-review MINOR 1: a place without its set time (PUT /api/config may leave none), where no trip can count, is
     * refused as not set: PUT /api/locations stamps it. Nothing sent.
     */
    @Test
    public void aPlaceWithoutItsSetTimeIsNotSet() throws Exception {
        // #110 review NIT 1: no place, or none set, is what the status names (the press refuses it first), not N1
        JsonObject none = heightSwitchStatus();
        assertEquals(HeightSwitch.PLACE_NOT_SET, none.get("kSwitchWhy").getAsString(), none.toString());
        assertEquals(HeightSwitch.PLACE_NOT_SET, none.get("needleWindowWhy").getAsString(), none.toString());
        ok("PUT", "/api/config", "{\"locations\":{\"height-switch\":{\"x\":150,\"y\":200,\"z\":0}}}");
        assertEquals(null, svc.getConfig().location(HeightSwitch.PLACE).setAtMs);
        JsonObject unset = heightSwitchStatus();
        assertEquals(HeightSwitch.PLACE_NOT_SET, unset.get("kSwitchWhy").getAsString(), unset.toString());
        assertEquals(HeightSwitch.PLACE_NOT_SET, unset.get("needleWindowWhy").getAsString(), unset.toString());
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("the height switch's place is not set: PUT /api/locations/height-switch"),
                lastBody);
    }

    /**
     * Review MINOR 7: the camera's pixel (needleEndMeasure.centrePx) holds for the camera location it was fitted at
     * (centrePxCameraXy). After a new calibration of the bottom camera both routes are refused before anything is sent,
     * naming both locations; Process's new fit lets the end be measured again.
     */
    @Test
    public void aNewCameraCalibrationRefusesTheEndUntilItsPixelIsFittedAgain() throws Exception {
        ok("PUT", "/api/locations/height-switch", "{\"x\":150,\"y\":200,\"z\":0}");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        camAt[0] += 0.1; // the camera moved 0.1 mm and OpenPnP's calibration followed it
        scene.camX += 0.1;
        for (String[] route : new String[][] {{"/api/needle/end/measure", null}, {"/api/height-switch/measure",
            "{\"tool\":\"needle\"}"}}) {
            sim.server.mark();
            call("POST", route[0], route[1]);
            assertEquals(409, lastStatus, lastBody);
            assertTrue(lastBody.contains("the bottom camera's location in OpenPnP (217.229, 152.319) is not the one its "
                    + "centre pixel was fitted at (217.129, 152.319)"), lastBody);
            assertEquals(new ArrayList<String>(), sim.server.sinceMark(), route[0] + ": nothing sent");
        }
        ok("PUT", "/api/config", "{\"needleEndMeasure\":{\"centrePx\":[957.5,534.8],\"centrePxCameraXy\":[217.229,"
                + "152.319]}}");
        JsonObject r = ok("POST", "/api/needle/end/measure", null);
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
    }

    /** Without N1's trip in this home, or without clean-check limits for the tip kind: refused, nothing sent. */
    @Test
    public void theNeedleWaitsForN1AndForItsTipKindsCleanLimits() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertEquals(HeightSwitch.PRESS_N1_FIRST, heightSwitchStatus().get("needleWindowWhy").getAsString());
        sim.server.mark();
        call("POST", "/api/height-switch/measure", "{\"tool\":\"needle\"}");
        assertEquals(409, lastStatus, lastBody);
        assertTrue(lastBody.contains("press N1 on the switch first"), lastBody);
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
        ok("POST", "/api/height-switch/measure", "{\"tool\":\"n1\",\"zStart\":21.4,\"zFloor\":19.6}");
        DispenseConfig c = svc.getConfig();
        c.needleEndMeasure.clean.remove(c.tip.key);
        svc.updateConfig(c);
        sim.server.mark();
        for (String[] route : new String[][] {{"/api/height-switch/measure", "{\"tool\":\"needle\"}"},
            {"/api/needle/end/measure", null}}) {
            call("POST", route[0], route[1]);
            assertEquals(409, lastStatus, lastBody);
            assertTrue(lastBody.contains("no clean-check thresholds for this tip kind: Process sets them"), lastBody);
        }
        assertEquals(new ArrayList<String>(), sim.server.sinceMark(), "nothing sent");
    }

    /**
     * #60 re-review MINOR 1: a needle change recorded while the needle's measure waits for its final lift's M400: the
     * old needle's trip must not be recorded after the change.
     */
    @Test
    public void aNeedleChangeDuringTheFinalLiftKeepsNoTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertTrue(svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120, TimeUnit.SECONDS).accepted);
        String lift = "G1 Z31.500 F600"; // the press's own (the end from below lifts at other speeds)
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), m400 = new CountDownLatch(1);
        try {
            long before = count(lift);
            sim.server.holdOkUntil.put(lift, first);
            Future<HeightSwitch.Result> f = svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5);
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
            HeightSwitch.Result r = f.get(120, TimeUnit.SECONDS);
            assertFalse(r.accepted && !r.refused, "a measure of the needle that came off: " + r.why);
            assertEquals("the needle was reported changed", r.why, "the change stops the task (WEB_API.md)");
            assertEquals("needle-changed", r.code, "#78's code: the UI keys on it");
            assertNull(HeightSwitch.needleTrip(svc.getConfig()));
        }
        finally {
            first.countDown();
            second.countDown();
            m400.countDown();
        }
    }

    /**
     * #110 review NIT 2: a needle change also stops N1's press (the engine's abort, at its next line): 409
     * needle-changed as for the needle, and no trip. (One after N1's last line stops nothing: its trip does not depend
     * on the needle.)
     */
    @Test
    public void aNeedleChangeStopsN1sPressWithNeedleChanged() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        String lift = "G1 Z31.500 F600";
        CountDownLatch held = new CountDownLatch(1);
        try {
            long before = count(lift);
            sim.server.holdOkUntil.put(lift, held);
            Future<HeightSwitch.Result> f = svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5);
            waitFor(() -> count(lift) == before + 1); // up to travel Z first, its ok held
            svc.recordNeedleChange("the needle came off", "test");
            sim.server.holdOkUntil.remove(lift);
            held.countDown();
            HeightSwitch.Result r = f.get(120, TimeUnit.SECONDS);
            assertTrue(r.refused, String.valueOf(r.why));
            assertEquals("needle-changed", r.code, r.why);
            assertNull(svc.getConfig().heightSwitch.n1, "no trip");
        }
        finally {
            held.countDown();
        }
    }

    /**
     * #60 re-review MINOR 2: a clock set back after the trip (emulated by a trip stamped an hour ahead): the change's
     * token still comes later than the trip, so the old trip is void; a new trip then counts at once.
     */
    @Test
    public void aClockSetBackDoesNotKeepTheOldNeedlesTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertTrue(svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120, TimeUnit.SECONDS).accepted);
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5)
                .get(120, TimeUnit.SECONDS).accepted);
        DispenseConfig c = svc.getConfig();
        c.heightSwitch.needle.atMs += 3_600_000;
        svc.updateConfig(c);
        svc.recordNeedleChange("the needle came off", "test");
        assertNull(HeightSwitch.needleTrip(svc.getConfig()), "the old needle's trip");
        // the new needle needs no checked landing (#60's stopgap is gone): its end from below aims it
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5)
                .get(120, TimeUnit.SECONDS).accepted);
        assertNotNull(HeightSwitch.needleTrip(svc.getConfig()), "the new needle's trip counts though the clock is behind");
        assertFalse(heightSwitchStatus().get("kSwitch").isJsonNull(), "in this home, since the place was set");
    }

    /**
     * #78 merged with the aim: after a clock set back a needle's trip is stamped at its needle's token, ahead of the
     * clock (#60 re-review MINOR 2), yet the switch's place set again must void it (re-review MINOR 1: another point of
     * the lever trips at another height). N1's and the needle's trips at the new place then count at once.
     */
    @Test
    public void aPlaceSetAgainAfterAClockSetBackStillVoidsTheTrips() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        assertTrue(svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120, TimeUnit.SECONDS).accepted);
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5)
                .get(120, TimeUnit.SECONDS).accepted);
        DispenseConfig c = svc.getConfig();
        c.heightSwitch.needle.atMs += 3_600_000; // measured before the clock was set back an hour
        svc.updateConfig(c);
        svc.recordNeedleChange("the needle came off", "test"); // its token an hour ahead of the clock
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5)
                .get(120, TimeUnit.SECONDS).accepted);
        assertEquals("trip", heightSwitchStatus().getAsJsonObject("needleWindow").get("from").getAsString());
        svc.setLocation(HeightSwitch.PLACE, 153, 200, 0); // 3 mm along the lever
        assertTrue(svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5).get(120, TimeUnit.SECONDS).accepted);
        JsonObject hs = heightSwitchStatus();
        assertEquals("kSwitch", hs.get("needleWindow").isJsonNull() ? hs.get("needleWindowWhy").getAsString()
                : hs.getAsJsonObject("needleWindow").get("from").getAsString(), "N1's trip at the new place counts, "
                        + "the needle's from the place before does not: " + hs);
        assertTrue(svc.measureHeightSwitch("needle", 5, NEEDLE_TRIP - 1, NEEDLE_TRIP + 0.5)
                .get(120, TimeUnit.SECONDS).accepted);
        assertFalse(heightSwitchStatus().get("kSwitch").isJsonNull(), "both trips at the new place count");
    }

    /**
     * #110 review MINOR 1: the switch's place set again while N1's press waits for its final lift's M400. Its trip is
     * of the place before (3 mm along the lever trips some 0.5 mm away): nothing is recorded, as counted at the new
     * place it would put the needle's K_switch that far off.
     */
    @Test
    public void aPlaceSetDuringThePressKeepsNoTrip() throws Exception {
        svc.setLocation(HeightSwitch.PLACE, 150, 200, 0);
        String lift = "G1 Z31.500 F600";
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), m400 = new CountDownLatch(1);
        try {
            long before = count(lift);
            sim.server.holdOkUntil.put(lift, first);
            Future<HeightSwitch.Result> f = svc.measureHeightSwitch("n1", 5, N1_TRIP + 1, N1_TRIP - 0.5);
            waitFor(() -> count(lift) == before + 1);
            sim.server.holdOkUntil.put(lift, second);
            first.countDown();
            waitFor(() -> count(lift) == before + 2); // the final lift
            long m400s = count("M400");
            sim.server.holdOkUntil.remove(lift);
            sim.server.holdOkUntil.put("M400", m400);
            second.countDown();
            waitFor(() -> count("M400") == m400s + 1); // the final lift's M400, its ok held
            svc.setLocation(HeightSwitch.PLACE, 153, 200, 0);
            m400.countDown();
            HeightSwitch.Result r = f.get(120, TimeUnit.SECONDS);
            assertTrue(r.refused, "a trip of the place before: " + r.why);
            assertEquals("the height switch's place was set again during the measure: nothing recorded, measure again",
                    r.why);
            assertNull(svc.getConfig().heightSwitch.n1, "no trip");
        }
        finally {
            first.countDown();
            second.countDown();
            m400.countDown();
        }
    }
}
