package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.ControllerCommands;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.machine.reference.camera.ImageCamera;
import org.openpnp.machine.reference.driver.GcodeDriver;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-49 + BR-53 on the simulated controller (docs/backend-requests.md "BR-49 + BR-53 API"): jog and move-to through
 * OpenPnP with the engine's rules, and the cameras read through OpenPnP's own capture.
 */
public class ControlApiSimTest {
    static final String UI = "ui";

    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        sim.driver.setCommand(null, GcodeDriver.CommandType.DISABLE_COMMAND, String.join("\n",
                ControllerCommands.STOP_SEQUENCE));
        svc = DispenseService.start(Configuration.get());
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        sim.server.delayBeforeOkMs.clear();
        api.stop();
        svc.shutdown();
        sim.close();
    }

    static final class Res {
        final int status;
        final String body;
        final String type;
        final String frameAt;

        Res(HttpResponse<String> r) {
            status = r.statusCode();
            body = r.body();
            type = r.headers().firstValue("Content-Type").orElse("");
            frameAt = r.headers().firstValue("X-Frame-At").orElse(null);
        }

        JsonObject json() {
            return new JsonParser().parse(body.isEmpty() ? "{}" : body).getAsJsonObject();
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 600 ? body.substring(0, 600) : body);
        }
    }

    Res call(String method, String path, String body, String who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (UI.equals(who)) {
            b.header(MachineLock.UI_HEADER, "ui");
        }
        else if (who != null) {
            b.header(MachineLock.TOKEN_HEADER, who);
        }
        b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    void refused(int status, String text, Res r) {
        assertEquals(status, r.status, r.toString());
        assertTrue(r.body.contains(text), "'" + text + "' in " + r);
    }

    static Location loc(org.openpnp.spi.HeadMountable hm) {
        return hm.getLocation().convertToUnits(LengthUnit.Millimeters);
    }

    @Test
    public void aJogMovesTheToolByThePress() throws Exception {
        Location n1 = loc(sim.n1);
        JsonObject r = ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        assertEquals(n1.getX() + 1.0, loc(sim.n1).getX(), 1e-6, r.toString());
        assertEquals(n1.getY(), loc(sim.n1).getY(), 1e-6);
        assertEquals(n1.getX() + 1.0, r.getAsJsonObject("to").get("x").getAsDouble(), 1e-6, r.toString());
        // a press is at most 10 mm in X/Y and 2 mm in Z
        refused(400, "10 mm", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":10.5}", UI));
        refused(400, "2 mm", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":2.5}", UI));
        refused(400, "tool", call("POST", "/api/jog", "{\"tool\":\"n9\",\"dx\":1}", UI));
        // X/Y or Z, never both in one press (re-review MINOR 2: X/Y only at travel height)
        refused(400, "not both", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1,\"dz\":-1}", UI));
        // one motion at a time: a second press while the first runs answers at once
        sim.server.delayBeforeOkMs.put("M400", 1500L);
        CompletableFuture<Res> first = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(400);
        refused(409, "another move", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        ok(first.get());
        sim.server.delayBeforeOkMs.clear();
    }

    /**
     * Re-review MINOR 3 and 5: the needle without a current frame stays in its safe zone, and a nozzle left below a
     * floor that rose (the frame went stale) can always be pressed up.
     */
    @Test
    public void aNozzleBelowAFloorThatRoseCanGoUp() throws Exception {
        needleFrameAndBlank();
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        for (int i = 0; i < 5; i++) {
            ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI));
        }
        double low = sim.n2.getSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        assertTrue(loc(sim.n2).getZ() < low, "the needle below its safe zone: " + loc(sim.n2));
        // a new needle: its frame is stale, its floor is its safe zone now
        ok(call("POST", "/api/needle/changed", "{}", UI));
        refused(409, "safe zone", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-0.1}", UI));
        ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":2}", UI));
        ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-2}", UI)); // N1 down raises the needle
    }

    @Test
    public void zFloorsAndTheUnhomedMachine() throws Exception {
        // the blank's probed surface at Z 5.5 under N1: N1 may touch it (a by-hand probe), never 0.5 mm into it
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}",
                UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":100,\"y\":40}", UI));
        Location at = loc(sim.n1);
        assertEquals(100, at.getX(), 1e-6);
        assertEquals(40, at.getY(), 1e-6);
        while (loc(sim.n1).getZ() - 2 >= 5.0 - 1e-9) {
            ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-2}", UI));
        }
        double z = loc(sim.n1).getZ();
        assertTrue(z < 7.0 + 1e-9, "lowered to " + z);
        Res low = call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-2}", UI);
        refused(409, "floor", low);
        assertTrue(low.body.contains("5.00"), "names the floor: " + low);
        assertEquals(z, loc(sim.n1).getZ(), 1e-6, "nothing moved");
        // the camera has no Z
        refused(409, "Z", call("POST", "/api/jog", "{\"tool\":\"camera\",\"dz\":1}", UI));
        // unhomed: nothing moves (on the see-saw a raise lowers the other nozzle; OpenPnP refuses unhomed motion too)
        sim.machine.setHomed(false);
        refused(409, "Home first", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-1}", UI));
        refused(409, "Home first", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        refused(409, "Home first", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":1}", UI));
    }

    /**
     * The needle's frame as the bench has it (ApiServerSimTest.setPlaces): contact over a 5.5 surface at Z 2.91, and
     * where its tip lands checked (on N2).
     */
    void needleFrameAndBlank() throws Exception {
        ok(call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":null,\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}",
                UI));
        ok(call("POST", "/api/needle/offset", "{\"needleChangedAtMs\":null,\"offsetMm\":-2.59}", UI));
        ok(call("PUT", "/api/surfaces/blank", "{\"points\":[[60,30,5.5],[200,30,5.5],[60,50,5.5],[200,50,5.5]]}",
                UI));
        landing(0);
    }

    /**
     * #19 review of BR-49, BLOCKER 1: N1 and the needle share one Z (a see-saw): raising N1 lowers the needle, so the
     * needle's floor holds for an N1 press too.
     */
    @Test
    public void theSeeSawRaisingN1NeverPushesTheNeedleBelowItsFloor() throws Exception {
        needleFrameAndBlank();
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        // the needle down, press by press, to its floor: the surface under it + its offset (5.5 - 2.59 = 2.91)
        int presses = 0;
        while (loc(sim.n2).getZ() - 2 >= 2.91 - 1e-9 && presses++ < 30) {
            ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI));
        }
        double needleZ = loc(sim.n2).getZ();
        refused(409, "floor 2.91", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI));
        // raising N1 by 2 mm would lower the needle by 2 mm: refused, naming the needle
        Res up = call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":2}", UI);
        refused(409, "the needle", up);
        assertTrue(up.body.contains("share one Z"), up.toString());
        assertEquals(needleZ, loc(sim.n2).getZ(), 1e-6, "nothing moved");
        // a small N1 raise that keeps the needle above its floor passes
        double room = needleZ - 2.91;
        if (room > 0.2) {
            ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":0.1}", UI));
        }
    }

    /**
     * #19 review of BR-49, BLOCKER 2 and MAJOR 3: X/Y moves only with both nozzles at travel height, and a move-to
     * ends with both inside their safe zones (never a tool left low at the new place).
     */
    @Test
    public void xyMovesOnlyAtTravelHeightAndMoveToEndsBothInTheirZones() throws Exception {
        double low = sim.n1.getSafeZ().convertToUnits(LengthUnit.Millimeters).getValue();
        int presses = 0;
        while (loc(sim.n1).getZ() >= low - 0.5 && presses++ < 30) {
            ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-2}", UI));
        }
        assertTrue(loc(sim.n1).getZ() < low, "N1 below its safe zone: " + loc(sim.n1));
        refused(409, "travel height", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        refused(409, "travel height", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dy\":1}", UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":150,\"y\":120}", UI));
        for (org.openpnp.spi.Nozzle n : new org.openpnp.spi.Nozzle[] {sim.n1, sim.n2}) {
            org.openpnp.model.Length[] zone = n.getSafeZZone();
            double z = loc(n).getZ();
            assertTrue(z >= zone[0].convertToUnits(LengthUnit.Millimeters).getValue() - 1e-6
                    && z <= zone[1].convertToUnits(LengthUnit.Millimeters).getValue() + 1e-6,
                    n.getName() + " inside its safe zone after the move-to: " + z);
        }
    }

    /**
     * BR-94: over the height switch (its place's circle) neither tool goes below its safe zone, also through the
     * see-saw, whatever its floor would allow (only the measure presses the switch); up is allowed; below its zone a
     * tool moves no X/Y at all, so never into the circle.
     */
    @Test
    public void overTheHeightSwitchNeitherToolGoesBelowItsSafeZone() throws Exception {
        needleFrameAndBlank(); // the needle's floor is its frame's then (1.81), far below its safe zone
        ok(call("PUT", "/api/locations/height-switch", "{\"x\":100,\"y\":100,\"z\":0}", UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"n1\",\"place\":\"height-switch\"}", UI));
        double z = loc(sim.n1).getZ();
        refused(409, "over the height switch", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":-1}", UI));
        Res seeSaw = call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":1}", UI);
        refused(409, "over the height switch", seeSaw);
        assertTrue(seeSaw.body.contains("N1 at Z") && seeSaw.body.contains("share one Z"), seeSaw.toString());
        assertEquals(z, loc(sim.n1).getZ(), 1e-6, "nothing moved");
        ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":1}", UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"place\":\"height-switch\"}", UI));
        refused(409, "over the height switch", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-1}", UI));
        // 30 mm away the needle goes below its zone to its frame's floor as before, and then moves no X/Y
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":130,\"y\":100}", UI));
        ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI));
        refused(409, "travel height", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dx\":-10}", UI));
    }

    /**
     * BR-94 review MINOR 6: over the height switch the needle is checked where its tip lands (N2 + the landing + trim,
     * as the measure and I12 aim), not at N2. With an 11 mm landing, N2 11 mm from the place puts the tip on it: a jog
     * down is refused; N2 on the place puts the tip 11 mm away: it may go down to its floor.
     */
    @Test
    public void overTheHeightSwitchTheNeedleIsCheckedWhereItsTipLands() throws Exception {
        needleFrameAndBlank();
        ok(call("PUT", "/api/locations/height-switch", "{\"x\":100,\"y\":100,\"z\":0}", UI));
        landing(11);
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":89,\"y\":100}", UI));
        refused(409, "over the height switch (100.0, 100.0)", call("POST", "/api/jog",
                "{\"tool\":\"needle\",\"dz\":-1}", UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"place\":\"height-switch\"}", UI));
        ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-1}", UI));
    }

    /**
     * BR-49's needle floor is taken where its tip lands too (N2 + the landing + trim), not at N2: N2 over the blank
     * (its floor 2.91), the tip 11 mm on over a raised surface (8.5): the floor is that surface's, 8.5 - 2.59 = 5.91,
     * and a press to Z 4.5, which the floor under N2 let through, is refused naming it and where the tip lands.
     */
    @Test
    public void theNeedlesFloorIsTheSurfaceWhereItsTipLands() throws Exception {
        needleFrameAndBlank();
        ok(call("PUT", "/api/surfaces/raised", "{\"points\":[[108,30,8.5],[120,30,8.5],[108,50,8.5],[120,50,8.5]]}",
                UI));
        landing(11);
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        for (int i = 0; i < 30 && loc(sim.n2).getZ() > 6.0 + 1e-6; i++) {
            ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":" + Math.max(-2, 6.0 - loc(sim.n2).getZ()) + "}",
                    UI));
        }
        assertEquals(6.0, loc(sim.n2).getZ(), 1e-6);
        Res r = call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-1.5}", UI);
        refused(409, "below its floor 5.91", r);
        assertTrue(r.body.contains("over (111.0, 40.0)"), "where its tip lands: " + r);
        assertEquals(6.0, loc(sim.n2).getZ(), 1e-6, "nothing moved");
        ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-0.05}", UI));
    }

    /**
     * The other way round (Backend, round 2's note 3): N2 over a raised surface, its tip 11 mm on over the blank. The
     * floor under N2 still holds: the higher of the two floors, as the rule before round 2 held N2's.
     */
    @Test
    public void theNeedlesFloorUnderN2StillHoldsWhereItIsHigherThanTheTips() throws Exception {
        needleFrameAndBlank();
        ok(call("PUT", "/api/surfaces/raised", "{\"points\":[[95,30,8.5],[105,30,8.5],[95,50,8.5],[105,50,8.5]]}",
                UI));
        landing(11);
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        for (int i = 0; i < 30 && loc(sim.n2).getZ() > 6.0 + 1e-6; i++) {
            ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":" + Math.max(-2, 6.0 - loc(sim.n2).getZ()) + "}",
                    UI));
        }
        assertEquals(6.0, loc(sim.n2).getZ(), 1e-6);
        Res r = call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-1.5}", UI);
        refused(409, "below its floor 5.91", r);
        assertTrue(r.body.contains("over (100.0, 40.0)"), "under N2: " + r);
        assertEquals(6.0, loc(sim.n2).getZ(), 1e-6, "nothing moved");
    }

    /** The mounted tip lands {@code dx} mm from N2 in X: a landing set under N2's head offset now. */
    void landing(double dx) throws Exception {
        DispenseConfig c = svc.getConfig();
        DispenseConfig.NeedleLanding l = new DispenseConfig.NeedleLanding();
        l.dxMm = dx;
        double[] ho = svc.headOffsetNow();
        l.headOffsetXMm = ho[0];
        l.headOffsetYMm = ho[1];
        l.setAtMs = System.currentTimeMillis();
        c.needleLanding = l;
        svc.updateConfig(c);
    }

    /**
     * BR-94 follow-up MINOR 1: the engine gone between the route's start and the floor check (a configuration load
     * restarts it) took the needle for N1: a press with no floor and no switch check. Refused, nothing moved.
     */
    @Test
    public void aZPressWithTheEngineGoneMidRouteIsRefused() throws Exception {
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        double z = loc(sim.n2).getZ();
        Field instance = DispenseService.class.getDeclaredField("instance");
        instance.setAccessible(true);
        // OpenPnP fires busy on its machine thread just before the press's task runs: the engine goes there
        MachineListener gone = new MachineListener.Adapter() {
            @Override
            public void machineBusy(Machine m, boolean busy) {
                try {
                    if (busy) {
                        instance.set(null, null);
                    }
                }
                catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        };
        sim.machine.addListener(gone);
        try {
            refused(409, "the dispense engine is not running", call("POST", "/api/jog",
                    "{\"tool\":\"needle\",\"dz\":-1}", UI));
        }
        finally {
            sim.machine.removeListener(gone);
            instance.set(null, svc);
        }
        assertEquals(z, loc(sim.n2).getZ(), 1e-6, "nothing moved");
    }

    /**
     * BR-94 follow-up MINOR 2: where the tip lands is the landing in effect, which a needle change keeps (and a change
     * of N2's head offset makes 0, 0) until a landing check, while a bent tip lands up to 15 mm away. With the frame
     * recorded again but the landing not checked, the needle's floor is its safe zone.
     */
    @Test
    public void withItsLandingNotCheckedTheNeedleStaysInItsSafeZone() throws Exception {
        needleFrameAndBlank();
        ok(call("POST", "/api/needle/changed", "{}", UI));
        long t = svc.getConfig().needleChangedAtMs;
        // the new needle's frame recorded again (K is refused after a needle change: both tips on the same spot)
        ok(call("POST", "/api/needle/anchor", "{\"needleChangedAtMs\":" + t
                + ",\"surfaceZ\":5.5,\"needleContactZ\":2.91}", UI));
        ok(call("POST", "/api/needle/offset", "{\"needleChangedAtMs\":" + t + ",\"offsetMm\":-2.59}", UI));
        ok(call("POST", "/api/move-to", "{\"tool\":\"needle\",\"x\":100,\"y\":40}", UI));
        double z = loc(sim.n2).getZ();
        refused(409, "where its tip lands is not checked", call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}",
                UI));
        assertEquals(z, loc(sim.n2).getZ(), 1e-6, "nothing moved");
        landing(0); // checked: the surface's floor (2.91) again
        ok(call("POST", "/api/jog", "{\"tool\":\"needle\",\"dz\":-2}", UI));
    }

    /** #19 review of BR-49, MINOR 7: a real unhomed state (STOP, then Start), not a faked flag. */
    @Test
    public void afterAStopAndStartNothingMovesUntilHomed() throws Exception {
        ok(call("POST", "/api/stop", null, null));
        long t0 = System.currentTimeMillis();
        while (sim.machine.isEnabled() && System.currentTimeMillis() - t0 < 20000) {
            Thread.sleep(50);
        }
        ok(call("POST", "/api/machine/enable", "{}", UI));
        refused(409, "Home first", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":1}", UI));
        refused(409, "Home first", call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":100,\"y\":100}", UI));
        ok(call("POST", "/api/machine/home", "{\"wait\":true}", UI));
        ok(call("POST", "/api/jog", "{\"tool\":\"n1\",\"dz\":1}", UI));
    }

    @Test
    public void moveToAPlaceEndsOverItAtSafeZ() throws Exception {
        ok(call("PUT", "/api/locations/waste", "{\"x\":80,\"y\":40,\"z\":5.5}", UI));
        JsonObject r = ok(call("POST", "/api/move-to", "{\"tool\":\"n1\",\"place\":\"waste\"}", UI));
        Location at = loc(sim.n1);
        assertEquals(80, at.getX(), 1e-6, r.toString());
        assertEquals(40, at.getY(), 1e-6);
        assertTrue(at.getZ() >= sim.n1.getSafeZ().convertToUnits(LengthUnit.Millimeters).getValue() - 1e-6,
                "at safe Z: " + at);
        refused(404, "nowhere", call("POST", "/api/move-to", "{\"tool\":\"n1\",\"place\":\"nowhere\"}", UI));
    }

    @Test
    public void refusedWhileAnAgentHoldsTheLockOrABatchRuns() throws Exception {
        String token = ok(call("POST", "/api/lock/acquire", "{\"who\":\"process\",\"what\":\"dots\"}", null))
                .get("token").getAsString();
        refused(409, "locked", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", UI));
        // the agent's own batch runs: its jog waits for nothing, it is refused
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        CompletableFuture<Res> batch = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", "/api/gcode", "{\"lines\":[\"G1 X120 F600\",\"M400\"]}", token);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        long t0 = System.currentTimeMillis();
        while (!api.getContext().passthrough.running() && System.currentTimeMillis() - t0 < 5000) {
            Thread.sleep(10);
        }
        refused(409, "batch", call("POST", "/api/jog", "{\"tool\":\"n1\",\"dx\":1}", token));
        batch.get();
    }

    /**
     * #21 review MAJOR 1: a move that times out while it runs must not answer "it will not run" (cancel(false) is true
     * for a running task too): it says the move did not end, and to look and press STOP.
     */
    @Test
    public void aMoveThatTimesOutWhileItRunsSaysSo() throws Exception {
        long before = ControlEndpoints.moveTimeoutMs;
        ControlEndpoints.moveTimeoutMs = 1000;
        try {
            sim.server.delayBeforeOkMs.put("M400", 3000L);
            Res r = call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":120,\"y\":90}", UI);
            refused(409, "did not end", r);
            assertTrue(r.body.contains("STOP") && !r.body.contains("will not run"), r.toString());
        }
        finally {
            ControlEndpoints.moveTimeoutMs = before;
            sim.server.delayBeforeOkMs.clear();
        }
    }

    @Test
    public void stopEndsAMoveTo() throws Exception {
        sim.server.delayBeforeOkMs.put("M400", 3000L);
        CompletableFuture<Res> move = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", "/api/move-to", "{\"tool\":\"n1\",\"x\":150,\"y\":150}", UI);
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(800);
        ok(call("POST", "/api/stop", null, null));
        Res r = move.get();
        assertEquals(409, r.status, r.toString());
        long t0 = System.currentTimeMillis();
        while (sim.machine.isEnabled() && System.currentTimeMillis() - t0 < 20000) {
            Thread.sleep(50);
        }
    }

    @Test
    public void theCamerasReadOnly() throws Exception {
        ImageCamera top = new ImageCamera();
        top.setName("Top");
        top.setLooking(Camera.Looking.Down);
        sim.machine.getDefaultHead().addCamera(top);
        ImageCamera bottom = new ImageCamera();
        bottom.setName("Bottom");
        bottom.setLooking(Camera.Looking.Up);
        sim.machine.addCamera(bottom);
        JsonObject list = new JsonParser().parse(ok2(call("GET", "/api/cameras", null, null))).getAsJsonObject();
        assertTrue(list.toString().contains("\"id\":\"top\"") && list.toString().contains("\"id\":\"bottom\""),
                list.toString());
        for (String id : new String[] {"top", "bottom"}) {
            long before = System.currentTimeMillis();
            Res f = call("GET", "/api/cameras/" + id + "/frame.jpg?w=320&settleMs=50", null, null);
            assertEquals(200, f.status, f.toString());
            assertTrue(f.type.startsWith("image/"), f.type);
            // when the frame was taken (UI 11-03: the picture's age), after the settle
            assertTrue(f.frameAt != null && Long.parseLong(f.frameAt) >= before + 50
                    && Long.parseLong(f.frameAt) <= System.currentTimeMillis(), "X-Frame-At " + f.frameAt);
        }
        assertEquals(404, call("GET", "/api/cameras/side/frame.jpg", null, null).status);
        assertEquals(405, call("POST", "/api/cameras/top/frame.jpg", "{}", UI).status);
        refused(400, "settleMs", call("GET", "/api/cameras/top/frame.jpg?settleMs=5000", null, null));
    }

    static String ok2(Res r) {
        assertTrue(r.status == 200, r.toString());
        return r.body;
    }
}
