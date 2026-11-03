package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BR-44: the status flags the deployer reads instead of texts. {@code machine.homing} is true from the start of any
 * home (the web's, the engine's, OpenPnP's own button) until it ends, also when a STOP ends it; {@code
 * server.startedAtMs} is the JVM's start; {@code dispense.state} tells a dispense engine still starting from one that
 * failed to start.
 */
public class StatusFlagsSimTest {
    SimMachine sim;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine(false);
        DispenseService.start(Configuration.get());
        sim.enableAndHome();
        ApiSettings s = new ApiSettings();
        s.port = 0;
        api = ApiServer.start(Configuration.get(), s);
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    public void after() throws Exception {
        sim.server.holdOkUntil.values().forEach(CountDownLatch::countDown);
        sim.server.holdOkUntil.clear();
        api.stop();
        DispenseService svc = DispenseService.get();
        if (svc != null) {
            svc.shutdown();
        }
        sim.close();
    }

    JsonObject call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        b.header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new JsonParser().parse(r.body()).getAsJsonObject();
    }

    JsonObject status() throws Exception {
        return call("GET", "/api/status", null);
    }

    boolean homing() throws Exception {
        return status().getAsJsonObject("machine").get("homing").getAsBoolean();
    }

    /** Polls until homing reads {@code want} (at most 10 s); whether it did. */
    boolean homingBecomes(boolean want) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < until) {
            if (homing() == want) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    /** G28's ok waits until the test opens the latch: a home the test ends. */
    CountDownLatch hold(String prefix) {
        CountDownLatch l = new CountDownLatch(1);
        sim.server.holdOkUntil.put(prefix, l);
        return l;
    }

    @Test
    public void homingIsTrueWhileAnyHomeRuns() throws Exception {
        assertFalse(homing(), status().toString());

        // the web's Home (through the engine's homeSafely)
        CountDownLatch g28 = hold("G28");
        CompletableFuture<JsonObject> web = CompletableFuture.supplyAsync(() -> {
            try {
                return call("POST", "/api/machine/home", "{\"wait\":true}");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(homingBecomes(true), "the web home");
        Thread.sleep(300);
        assertTrue(homing(), "still homing while G28 is unanswered");
        g28.countDown();
        assertTrue(web.get(30, TimeUnit.SECONDS).get("homed").getAsBoolean());
        assertTrue(homingBecomes(false), "after the web home");

        // OpenPnP's own Home button: machine.home() in a machine task
        g28 = hold("G28");
        Future<Object> own = sim.machine.submit(() -> {
            sim.machine.home();
            return null;
        });
        assertTrue(homingBecomes(true), "OpenPnP's own home");
        g28.countDown();
        own.get(30, TimeUnit.SECONDS);
        assertTrue(homingBecomes(false), "after OpenPnP's own home");

        // a STOP inside G28: still a home until G28 is answered (or its wait times out), then it ends
        g28 = hold("G28");
        sim.machine.submit(() -> {
            sim.machine.home();
            return null;
        });
        assertTrue(homingBecomes(true), "the third home");
        call("POST", "/api/stop", null);
        Thread.sleep(300);
        assertTrue(homing(), "G28 unanswered after the STOP: still homing");
        g28.countDown();
        assertTrue(homingBecomes(false), "the stopped home ended: " + status().getAsJsonObject("machine"));
    }

    @Test
    public void startedAtMsIsTheJvmStart() throws Exception {
        long jvm = ManagementFactory.getRuntimeMXBean().getStartTime();
        assertEquals(jvm, status().getAsJsonObject("server").get("startedAtMs").getAsLong(), 1000);
    }

    @Test
    public void dispenseStateTellsAFailedStartFromARunningEngine() throws Exception {
        JsonObject d = status().getAsJsonObject("dispense");
        assertEquals("running", d.get("state").getAsString(), d.toString());

        // a settings file that cannot be read: the engine fails to start, and says so
        File f = new File(new File(sim.configDir, DispenseConfig.SUBDIR), DispenseConfig.FILE_NAME);
        f.getParentFile().mkdirs();
        byte[] good = f.exists() ? Files.readAllBytes(f.toPath()) : null;
        Files.write(f.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        assertThrows(Exception.class, () -> DispenseService.start(Configuration.get()));
        assertEquals(null, DispenseService.get(), "no stopped engine is left in its place");
        d = status().getAsJsonObject("dispense");
        assertEquals("failed", d.get("state").getAsString(), d.toString());
        assertFalse(d.get("startError").isJsonNull(), d.toString());
        assertTrue(d.get("unavailableReason").getAsString().contains("failed to start"), d.toString());

        if (good != null) {
            Files.write(f.toPath(), good);
        }
        else {
            Files.delete(f.toPath());
        }
        DispenseService.start(Configuration.get());
        d = status().getAsJsonObject("dispense");
        assertEquals("running", d.get("state").getAsString(), d.toString());
        assertTrue(d.get("startError").isJsonNull(), d.toString());
    }
}
