package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * The web UI of missing paste profiles on a simulated machine (PROFILE_CALIBRATION_DESIGN.md §13, §14.2): index.html
 * loads padprofile.js and padprofile.css after the board import and before the workspaces, both are served, every hook
 * line of §13.9 is in its owner's file (a merge that drops one leaves the page without that part, silently), and the
 * board the browser test uploads (src/test/resources/padprofile/padcal.kicad_pcb, §14.3's board as a KiCad file) gives
 * the groups the browser test and the screenshots rely on.
 */
public class PadProfileUiSimTest {
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
        final String body;
        final String contentType;

        Res(HttpResponse<byte[]> r) {
            status = r.statusCode();
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

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static String str(JsonObject o, String k) {
        return o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsString();
    }

    // ------------------------------------------------------------------------------------------
    // The static files and the hook lines
    // ------------------------------------------------------------------------------------------

    @Test
    public void indexLoadsThePadProfileFilesAfterTheBoardImport() throws Exception {
        String index = get("/").body;
        for (String f : new String[] {"padprofile.js", "padprofile.css"}) {
            assertTrue(index.contains("/static/" + f), "index.html loads " + f);
            Res r = get("/static/" + f);
            assertEquals(200, r.status, f);
            assertFalse(r.body.isEmpty(), f);
            assertTrue(r.contentType.startsWith(f.endsWith(".js") ? "text/javascript" : "text/css"),
                    f + " " + r.contentType);
        }
        // the module registers IL.padProfile before profile.js asks for its hooks, after the board import it extends
        int css = index.indexOf("/static/padprofile.css");
        assertTrue(index.indexOf("/static/boardimport.css") < css, "padprofile.css after boardimport.css");
        int js = index.indexOf("/static/padprofile.js");
        assertTrue(index.indexOf("/static/boardimport.js") < js, "padprofile.js after boardimport.js");
        for (String later : new String[] {"run.js", "profile.js", "calibrate.js", "app.js"}) {
            assertTrue(js < index.indexOf("/static/" + later), "padprofile.js before " + later);
        }
        assertTrue(index.contains("<script src=\"/static/padprofile.js\" defer></script>"),
                "deferred like the modules");
    }

    @Test
    public void everyHookLineIsInItsOwnersFile() throws Exception {
        Map<String, String[]> hooks = new TreeMap<>();
        hooks.put("core.js", new String[] {"IL.padProfile.padTypesItem(plan, d, viol)",
            "'pad calibration': 'Pad calibration'", "IL.padProfile.taskParts(run)", "IL.padProfile.jobLock(s)",
            "IL.padProfile.verdict(s)", "IL.openView('pad-cal'"});
        hooks.put("profile.js", new String[] {"IL.padProfile.profileHooks(IL.pt)", "IL.pt.planStats()",
            "IL.pt.route(r)", "IL.pt.panel(parts, s, first)", "IL.pt.sidePanelExtras", "IL.pt.editorExtras",
            "IL.pt.navSubs", "'pad calibration': 'Pad calibration'"});
        hooks.put("run.js", new String[] {"IL.padProfile.needsHref()"});
        hooks.put("records.js", new String[] {"case 'pad-ladder'", "case 'pad-weigh'"});
        hooks.put("boardimport.js", new String[] {"IL.padProfile.importRows(n.pkgs, pv)",
            "IL.padProfile.importSaved(pv", "p.outcome === 'needs-profile'"});
        hooks.put("padinspect.js", new String[] {"IL.padProfile.inspectorBlock(", "IL.padProfile.amountText(pad)",
            "id: 'pad-insp-profile'"});
        hooks.put("boardview.js", new String[] {"drawSugg(", "class: 'pv-sugg'", "L('sugg')", "c.dataset.d"});
        hooks.put("calibrate.js", new String[] {"'pad calibration']", "card('cal-padcal'", "card('cal-weigh'",
            "id: 'btn-guide-ladder'", "camera · records only (no phase lock)"});
        for (Map.Entry<String, String[]> e : hooks.entrySet()) {
            String body = get("/static/" + e.getKey()).body;
            for (String line : e.getValue()) {
                assertTrue(body.contains(line), e.getKey() + " has the hook: " + line);
            }
        }
        // no native dialogs or code from strings in the module (STOP must stay live; CSP script-src 'self')
        String js = get("/static/padprofile.js").body;
        for (String bad : new String[] {"confirm(", "alert(", "prompt(", "showModal(", "eval(", "new Function(",
            "innerHTML", "javascript:"}) {
            assertFalse(js.contains(bad), "padprofile.js uses " + bad);
        }
        assertTrue(js.contains("IL.padProfile = {"), "the module defines IL.padProfile");
        assertTrue(js.contains("IL.view('pad-cal'"), "the pad-cal view is registered");
    }

    // ------------------------------------------------------------------------------------------
    // The browser test's board
    // ------------------------------------------------------------------------------------------

    static byte[] padcal() throws Exception {
        try (InputStream in = PadProfileUiSimTest.class.getResourceAsStream("/padprofile/padcal.kicad_pcb")) {
            assertNotNull(in, "src/test/resources/padprofile/padcal.kicad_pcb");
            return in.readAllBytes();
        }
    }

    @Test
    public void thePadcalBoardNeedsThreeProfilesAndTheTipHoldsTwoGroups() throws Exception {
        ok(send("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}".getBytes(StandardCharsets.UTF_8),
                "application/json"));
        JsonObject pv = ok(send("POST", "/api/boards/import?file=" + URLEncoder.encode("padcal.kicad_pcb", "UTF-8"),
                padcal(), "application/octet-stream"));
        JsonObject counts = pv.getAsJsonObject("counts");
        assertEquals(11, counts.get("placements").getAsInt(), counts.toString());
        assertEquals(3, counts.get("fiducials").getAsInt(), counts.toString());
        assertEquals(29, counts.get("pastePads").getAsInt(), counts.toString());
        // one group-1 NEEDS_PROFILE warning and no NO_PAD_TYPE (§12.6)
        int needs = 0;
        for (JsonElement w : pv.getAsJsonArray("warnings")) {
            JsonObject o = w.getAsJsonObject();
            assertFalse("NO_PAD_TYPE".equals(str(o, "code")), o.toString());
            if ("NEEDS_PROFILE".equals(str(o, "code"))) {
                needs++;
                assertEquals(1, o.get("group").getAsInt());
                assertTrue(str(o, "text").startsWith("4 packages need a profile: "), o.toString());
            }
        }
        assertEquals(1, needs);
        // per package: the reason and the suggestion the browser test clicks (§14.3: J, Q new; T use; U the tip)
        Map<String, List<String>> got = new TreeMap<>();
        for (JsonElement e : pv.getAsJsonArray("packages")) {
            JsonObject p = e.getAsJsonObject();
            List<String> l = new ArrayList<>();
            if (p.get("profileCheck") != null && !p.get("profileCheck").isJsonNull()) {
                for (JsonElement x : p.getAsJsonArray("profileCheck")) {
                    JsonObject g = x.getAsJsonObject();
                    JsonObject s = g.getAsJsonObject("suggestion");
                    JsonObject dots = s.get("dots") == null || s.get("dots").isJsonNull() ? null
                            : s.getAsJsonObject("dots");
                    l.add(str(g, "reason") + " " + str(s, "kind") + (dots == null ? ""
                            : " " + dots.get("perPad").getAsInt() + "x" + dots.get("steps").getAsInt()));
                }
            }
            got.put(str(p, "baseId"), l);
        }
        assertEquals(List.of("no-type use 1x800"), got.get("TestChip_A"), got.toString());
        assertEquals(List.of("no-type new 2x800"), got.get("SW_SPST_3.9x2.9mm"), got.toString());
        assertEquals(List.of("no-type new 2x400"), got.get("Test_Strip_0.62x1.50"), got.toString());
        assertEquals(List.of("tip none", "tip none"), got.get("WSON-8-1EP_2x2mm_P0.5mm_EP0.9x1.6mm"), got.toString());
        assertEquals(List.of(), got.get("R_0603_1608Metric"), got.toString());
        Map<String, String> outcome = new TreeMap<>();
        for (JsonElement e : pv.getAsJsonArray("placements")) {
            outcome.put(str(e.getAsJsonObject(), "id"), str(e.getAsJsonObject(), "outcome"));
        }
        for (String ref : new String[] {"T1", "J1", "J2", "Q1", "Q2", "Q3"}) {
            assertEquals("needs-profile", outcome.get(ref), ref);
        }
        assertEquals("not-dispensed", outcome.get("U1"));
        for (String ref : new String[] {"R1", "R2", "R3", "R4"}) {
            assertEquals("paste", outcome.get(ref), ref);
        }
    }
}
