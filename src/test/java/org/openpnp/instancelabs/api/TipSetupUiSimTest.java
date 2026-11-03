package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.model.Configuration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The web UI of tip setups on a simulated machine (PROFILE_CALIBRATION_DESIGN.md §22.7, §22.9 ts-web): index.html loads
 * tipsetup.js and tipsetup.css after the pad profiles and before the workspaces, both are served, the strip's Tip cell
 * is in the frame, every hook line of §22.7 is in its owner's file (a merge that drops one leaves the page without that
 * part, silently), and the contracts the browser test relies on hold: the 25G straight preset adds the setup the test
 * mounts ({@code 25g-straight}), the status carries what the strip cell reads, and the fixture picture is stored as the
 * setup's main picture.
 */
public class TipSetupUiSimTest {
    SimMachine sim;
    DispenseService svc;
    ApiServer api;
    HttpClient http;

    @BeforeEach
    public void before() throws Exception {
        sim = new SimMachine();
        FlatVacuum.install(sim, FlatVacuum.scene(5.5, null, 0));
        svc = DispenseService.start(Configuration.get());
        assertTrue(svc.isAvailable(), String.valueOf(svc.getUnavailableReason()));
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

    static final class Res {
        final int status;
        final byte[] bytes;
        final String body;
        final String contentType;

        Res(HttpResponse<byte[]> r) {
            status = r.statusCode();
            bytes = r.body();
            body = new String(r.body(), StandardCharsets.UTF_8);
            contentType = r.headers().firstValue("Content-Type").orElse("");
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        @Override
        public String toString() {
            return status + " " + (body.length() > 3000 ? body.substring(0, 3000) + "…" : body);
        }
    }

    Res send(String method, String path, byte[] body, String contentType) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.getPort() + path))
                .timeout(Duration.ofSeconds(120));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        return new Res(http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    Res get(String path) throws Exception {
        return send("GET", path, null, null);
    }

    Res json(String method, String path, String body) throws Exception {
        return send(method, path, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static String str(JsonObject o, String k) {
        return o == null || o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsString();
    }

    // ------------------------------------------------------------------------------------------
    // The static files and the hook lines
    // ------------------------------------------------------------------------------------------

    @Test
    public void indexLoadsTheTipSetupFilesAfterThePadProfiles() throws Exception {
        String index = get("/").body;
        for (String f : new String[] {"tipsetup.js", "tipsetup.css"}) {
            assertTrue(index.contains("/static/" + f), "index.html loads " + f);
            Res r = get("/static/" + f);
            assertEquals(200, r.status, f);
            assertFalse(r.body.isEmpty(), f);
            assertTrue(r.contentType.startsWith(f.endsWith(".js") ? "text/javascript" : "text/css"),
                    f + " " + r.contentType);
        }
        // IL.tips exists before setup.js builds its card and profile.js reads the library; after padprofile.js, whose
        // pad-cal view the landing check and Measure its dots open
        int css = index.indexOf("/static/tipsetup.css");
        assertTrue(index.indexOf("/static/padprofile.css") < css, "tipsetup.css after padprofile.css");
        int js = index.indexOf("/static/tipsetup.js");
        assertTrue(index.indexOf("/static/padprofile.js") < js, "tipsetup.js after padprofile.js");
        for (String later : new String[] {"run.js", "setup.js", "profile.js", "calibrate.js", "records.js", "app.js"}) {
            assertTrue(js < index.indexOf("/static/" + later), "tipsetup.js before " + later);
        }
        assertTrue(index.contains("<script src=\"/static/tipsetup.js\" defer></script>"), "deferred like the modules");
        // the strip's Tip cell, after the purge row, before the verdict (§22.7)
        int tip = index.indexOf("id=\"chip-tip\"");
        assertTrue(tip > index.indexOf("id=\"chip-purge\"") && tip < index.indexOf("id=\"pill-engine\""), "#chip-tip in the strip");
    }

    @Test
    public void everyHookLineIsInItsOwnersFile() throws Exception {
        Map<String, String[]> hooks = new TreeMap<>();
        hooks.put("core.js", new String[] {"IL.tips.cellView(s)", "$('chip-tip')", "'tip picture': 'Tip picture'",
            "NEEDLE_TICKS = new Set(['regulator', 'prime-seen', 'test-dot-seen', 'tip-on'])",
            "where it lands has not been checked", "IL.fix('reload-page'"});
        hooks.put("setup.js", new String[] {"IL.tips.buildCard(c)", "IL.tips.renderCard(s", "IL.tips.openChange({ again: true })",
            "'/api/needle/xy-trim'", "body.forTip", "'tip', 'parkedTips', 'needleLanding', 'needleXyTrim'", "anchor-k-cap"});
        hooks.put("profile.js", new String[] {"id: 'pt-by-tip'", "id: 'pt-side-tips'", "forTip: PF.tipKey",
            "body.forSetup", "body.padVersion", "'Retire for every tip…'", "unsaved: () =>", "pf-tip-change"});
        hooks.put("padprofile.js", new String[] {"id: 'pt-needs-for'", "id: 'pt-needs-measure'", "id: 'btn-needs-measure'",
            "id: 'pcal-landing'", "id: 'pcal-tipdots'", "id: 'pcal-tip'", "id: 'btn-pcal-wrongtip'", "'pcal-label-' + id",
            "id: 'pt-calfor'", "id: 'pt-sugg-tips'", "MODE_TITLE"});
        hooks.put("records.js", new String[] {"id: 'rec-pcals-t'", "id: 'rec-cals-old'", "case 'tip-picture'", "id: 'rec-label'",
            "id: 'btn-rec-upload'"});
        hooks.put("run.js", new String[] {"'run-tick-tip'", "tipT.lab.id = 'run-confirm-tip'", "' · for the ' + p.setupName"});
        hooks.put("calibrate.js", new String[] {"class: 'guide-photo'", "'the mounted tip (Setup › Dispenser & tip)'"});
        for (Map.Entry<String, String[]> e : hooks.entrySet()) {
            String body = get("/static/" + e.getKey()).body;
            for (String line : e.getValue()) {
                assertTrue(body.contains(line), e.getKey() + " has the hook: " + line);
            }
        }
        // the module: the ids of §22.7, no native dialogs or code from strings (STOP must stay live; CSP script-src 'self')
        String js = get("/static/tipsetup.js").body;
        for (String id : new String[] {"'tip-photo'", "'tip-photo-none'", "'tip-photo-cap'", "'btn-tip-upload'", "'tip-file'",
            "'btn-tip-picture'", "'tip-name'", "'tip-kv'", "'tip-next'", "'btn-tip-change'", "'tip-more'", "'btn-tip-add'",
            "'btn-tip-edit'", "'btn-tip-retire'", "'tip-swap'", "'tip-list'", "'tip-retired'", "'tip-change-what'",
            "'btn-tip-mount'", "'tip-change-why'", "'btn-tip-undo'", "'dlg-tip-add'", "'ta-preset'", "'ta-name'",
            "'ta-dispenser'", "'ta-model'", "'ta-gauge'", "'ta-id'", "'ta-od'", "'ta-od-src'", "'ta-form'", "'ta-bend'",
            "'ta-toward'", "'ta-taper'", "'ta-length'", "'ta-mindot'", "'ta-profile-from'", "'ta-notes'", "'ta-photo'",
            "'ta-photo-img'", "'ta-key'", "'ta-save'", "'ta-save-mount'", "'photo-view'", "'btn-photo-main'",
            "'btn-photo-remove'"}) {
            assertTrue(js.contains(id), "tipsetup.js has the id " + id);
        }
        for (String bad : new String[] {"confirm(", "alert(", "prompt(", "showModal(", "eval(", "new Function(",
            "innerHTML", "javascript:"}) {
            assertFalse(js.contains(bad), "tipsetup.js uses " + bad);
        }
        assertTrue(js.contains("IL.tips = {"), "the module defines IL.tips");
        assertTrue(js.contains("IL.photos = {"), "the module defines IL.photos");
        assertTrue(js.contains("IL.view('tip-change'"), "the change view is registered");
        String css = get("/static/tipsetup.css").body;
        assertTrue(css.contains(".dialog--lg { width: min(1100px, 100%); }"), "#photo-view fits the dialog, never the viewport");
    }

    // ------------------------------------------------------------------------------------------
    // The browser test's contracts
    // ------------------------------------------------------------------------------------------

    /** The 25G straight preset of the Add a tip dialog, as the browser sends it. */
    static final String PRESET_25 = "{\"name\":\"25G straight\",\"dispenser\":\"Vieweg DV-5005\",\"tipModel\":\"DSKIT1 25G\","
            + "\"gauge\":25,\"idMm\":0.25,\"odMm\":0.51,\"odSource\":\"assumed\",\"form\":\"straight\",\"bendDeg\":null,"
            + "\"bendToward\":null,\"taper\":\"straight-steel\",\"lengthMm\":12.7,\"minDotSteps\":800,\"notes\":null,"
            + "\"profileFrom\":\"ad22tta-b\"}";

    @Test
    public void thePresetTheBrowserTestAddsIsThe25gStraightAndItsPictureIsItsMainPicture() throws Exception {
        // the strip cell and the card read the mounted tip from the status (§22.6)
        JsonObject tip = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("tip");
        for (String k : new String[] {"key", "name", "setupVersion", "dispenser", "form", "summary", "complete", "mountedAtMs",
            "dots", "thumbUrl", "undoUntilMs"}) {
            assertTrue(tip.has(k), "status dispense.tip." + k + " in " + tip);
        }
        assertEquals("22G bent taper", str(tip, "name"));
        assertEquals("bench", str(tip, "dots"));
        // the preset: the key the browser test mounts and the Playwright SIM scene's --paste-by-tip names
        Res r = json("POST", "/api/tip-setups", PRESET_25);
        assertEquals(201, r.status, r.toString());
        JsonObject setup = r.json().getAsJsonObject("setup");
        assertEquals("25g-straight", str(setup, "key"));
        assertEquals("Added. It has no pad profiles yet.", str(r.json(), "note"));
        assertTrue(setup.get("complete").getAsBoolean(), setup.toString());
        assertEquals("18 psi half turn · 25G straight", str(setup.getAsJsonObject("profile"), "name"));
        // the fixture picture the test drops on the dialog: stored as the setup's picture, its main one
        Path png = Paths.get("src", "test", "web", "fixtures", "tips", "tip-25g-straight.png");
        assertTrue(Files.isRegularFile(png), png.toString());
        Res up = send("POST", "/api/photos?kind=setup&setup=25g-straight&caption=" + URLEncoder.encode("Picture of the 25G straight",
                "UTF-8"), Files.readAllBytes(png), "image/jpeg");
        assertEquals(201, up.status, up.toString());
        JsonObject photo = up.json().getAsJsonObject("photo");
        assertEquals("setup", str(photo, "kind"));
        assertTrue(photo.get("removable").getAsBoolean());
        Res img = get(str(photo, "url"));
        assertEquals(200, img.status);
        assertTrue(img.contentType.startsWith("image/jpeg"), img.contentType);
        JsonObject list = ok(get("/api/tip-setups?retired=true"));
        JsonObject s25 = null;
        for (JsonElement e : list.getAsJsonArray("setups")) {
            if ("25g-straight".equals(str(e.getAsJsonObject(), "key"))) {
                s25 = e.getAsJsonObject();
            }
        }
        assertNotNull(s25, list.toString());
        assertEquals(photo.get("id").getAsLong(), s25.getAsJsonObject("mainPhoto").get("id").getAsLong());
        assertEquals("none", str(s25.getAsJsonObject("dots"), "source"), "a new tip measured nothing yet");
        // the presets' chart values are §22.7's: a wrong one would fill the form with another needle's bore
        String js = get("/static/tipsetup.js").body;
        assertTrue(js.contains("[25, 0.25, 0.51]"), "the 25G straight's ID 0.25 and OD 0.51");
        assertTrue(js.contains("name: '22G bent taper', tipModel: 'ADL AD22TTA-B', gauge: 22, idMm: 0.41, odMm: 0.72, form: 'bent', bendDeg: 30"),
                "the 22G bent taper preset");
    }

    @Test
    public void aNeedleValueWithoutItsTokenIsRefusedAsTheWebUiExpects() throws Exception {
        // the needle frame card sends the token of the moment the heights were taken (§22.3): without it, 400
        Res bad = json("POST", "/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59}");
        assertEquals(400, bad.status, bad.toString());
        Long token = null;
        JsonObject needle = ok(get("/api/status")).getAsJsonObject("dispense").getAsJsonObject("needle");
        if (needle.get("changedAtMs") != null && !needle.get("changedAtMs").isJsonNull()) {
            token = needle.get("changedAtMs").getAsLong();
        }
        ok(json("POST", "/api/needle/anchor", "{\"surfaceZ\":5.5,\"needleContactRawSum\":65.59,\"needleChangedAtMs\":" + token + "}"));
        // "I changed the needle" from Change tip: a mount of the mounted tip; a value taken before it is refused
        ok(json("POST", "/api/tip-setups/ad22tta-b/mount", "{\"why\":\"test\"}"));
        Res stale = json("POST", "/api/needle/offset", "{\"offsetMm\":-2.59,\"needleChangedAtMs\":" + token + "}");
        assertEquals(409, stale.status, stale.toString());
        assertEquals("needle-changed", str(stale.json(), "error"));
        assertTrue(str(stale.json(), "reason").startsWith("The needle was changed at "), stale.toString());
    }
}
