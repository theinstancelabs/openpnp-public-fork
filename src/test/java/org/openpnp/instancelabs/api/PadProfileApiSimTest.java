package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseRecord;
import org.openpnp.instancelabs.dispense.FlatVacuum;
import org.openpnp.instancelabs.dispense.SimMachine;
import org.openpnp.instancelabs.padprofile.GroupKey;
import org.openpnp.instancelabs.sim.SimGuard;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Missing paste profiles through the API on a simulated machine (PROFILE_CALIBRATION_DESIGN.md §12, §14.2): the
 * profile check of the KiCad upload preview, of board files and of plans, applying suggestions (Use, Save), mappings,
 * the paste, the calibration area, the status evidence of an own dose, the SIM-only floors, the refusals while a task
 * runs and the plan view's new fields. The nano split of the calibration brief (84 pads too fine for the 22G tip, 41
 * dispensable) comes out of the API on both sides of the board.
 */
public class PadProfileApiSimTest {
    static final String NANO = "NanoV3.3.kicad_pcb.gz";

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

    // ------------------------------------------------------------------------------------------
    // HTTP and fixtures
    // ------------------------------------------------------------------------------------------

    static final class Res {
        final int status;
        final String body;

        Res(HttpResponse<byte[]> r) {
            status = r.statusCode();
            body = new String(r.body(), StandardCharsets.UTF_8);
        }

        JsonObject json() {
            return new JsonParser().parse(body).getAsJsonObject();
        }

        JsonArray array() {
            return new JsonParser().parse(body).getAsJsonArray();
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

    Res call(String method, String path, String json) throws Exception {
        return send(method, path, json == null ? null : json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    static JsonObject ok(Res r) {
        assertTrue(r.status >= 200 && r.status < 300, r.toString());
        return r.json();
    }

    static JsonObject refused(Res r, int status, String code) {
        assertEquals(status, r.status, r.toString());
        JsonObject o = r.json();
        assertEquals(code, o.get("error").getAsString(), r.toString());
        return o;
    }

    static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    JsonObject preview(byte[] b, String file) throws Exception {
        return ok(send("POST", "/api/boards/import?file=" + enc(file), b, "application/octet-stream"));
    }

    JsonObject plan(File board) throws Exception {
        return ok(call("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + board.getCanonicalPath()
                .replace("\\", "\\\\") + "\",\"x\":100,\"y\":80,\"z\":5.5,\"rotation\":0}"));
    }

    JsonObject check(String query) throws Exception {
        return ok(get("/api/pad-profile/check?" + query));
    }

    JsonObject checkBoard(File board) throws Exception {
        return check("boardFile=" + enc(board.getCanonicalPath()));
    }

    void characterize(String key) throws Exception {
        ok(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}"));
    }

    static JsonObject group(JsonObject check, String packageId) {
        JsonObject found = null;
        for (JsonElement e : check.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            if (g.getAsJsonArray("packageIds").get(0).getAsString().equals(packageId)) {
                assertNull(found, "one group of " + packageId);
                found = g;
            }
        }
        return found;
    }

    static JsonObject pkgOf(JsonObject preview, String id) {
        for (JsonElement e : preview.getAsJsonArray("packages")) {
            if (id.equals(str(e.getAsJsonObject(), "id"))) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    static String str(JsonObject o, String k) {
        return o.get(k) == null || o.get(k).isJsonNull() ? null : o.get(k).getAsString();
    }

    // ---- the nano pads of src/test/resources/padprofile (R/README.md) ----------------------------------------------

    static final class Fixture {
        List<FPart> parts = new ArrayList<>();
        List<FPad> pads = new ArrayList<>();
    }

    static final class FPart {
        String ref;
        String packageId;
        String side;
        double x;
        double y;
        double rotationDeg;
    }

    static final class FPad {
        String padId;
        String ref;
        String packageId;
        String padName;
        String side;
        double x;
        double y;
        double rotationDeg;
        double wMm;
        double hMm;
        double roundness;
        double localX;
        double localY;
    }

    static Fixture pads(String board) throws Exception {
        try (InputStream in = PadProfileApiSimTest.class.getResourceAsStream("/padprofile/" + board + ".pads.json")) {
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Fixture.class);
        }
    }

    static byte[] kicad(String name) throws Exception {
        return BoardImportSimTest.fixture(name);
    }

    /**
     * One side of a fixture board as an OpenPnP board file: a package per footprint with the openings as its pads,
     * every part on the top of the file (the fixture's bottom pads are already in the board frame), listed in OpenPnP.
     */
    File boardFile(Fixture f, String side) throws Exception {
        Map<String, FPart> parts = new LinkedHashMap<>();
        for (FPart p : f.parts) {
            if (side.equals(p.side)) {
                parts.put(p.ref, p);
            }
        }
        Map<String, Footprint> footprints = new TreeMap<>();
        Map<String, String> firstRef = new TreeMap<>();
        for (FPad p : f.pads) {
            if (!side.equals(p.side) || !p.ref.equals(firstRef.computeIfAbsent(p.packageId, k -> p.ref))) {
                continue;
            }
            Footprint fp = footprints.computeIfAbsent(p.packageId, k -> {
                Footprint x = new Footprint();
                x.setUnits(LengthUnit.Millimeters);
                return x;
            });
            Footprint.Pad pad = new Footprint.Pad();
            pad.setName(p.padName);
            pad.setX(p.localX);
            pad.setY(p.localY);
            pad.setWidth(p.wMm);
            pad.setHeight(p.hMm);
            pad.setRotation(p.rotationDeg - parts.get(p.ref).rotationDeg);
            pad.setRoundness(p.roundness);
            fp.addPad(pad);
        }
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"nano-" + side + "\">\n"
                + "<dimensions units=\"Millimeters\" x=\"43.18\" y=\"17.78\" z=\"0.0\" rotation=\"0.0\"/>\n"
                + "<placements>\n");
        for (FPart p : parts.values()) {
            sb.append("<placement version=\"1.4\" side=\"Top\" id=\"").append(p.ref).append("\" part-id=\"nano-")
                    .append(p.packageId).append("\" type=\"Placement\" enabled=\"true\" rank=\"0\">\n")
                    .append("<location units=\"Millimeters\" x=\"").append(p.x).append("\" y=\"").append(p.y)
                    .append("\" z=\"0.0\" rotation=\"").append(p.rotationDeg).append("\"/>\n")
                    .append("<error-handling>Default</error-handling>\n</placement>\n");
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File file = new File(sim.configDir, "nano-" + side + ".board.xml");
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        EdtRunner.call(() -> {
            Configuration c = Configuration.get();
            for (Map.Entry<String, Footprint> e : footprints.entrySet()) {
                Package pkg = c.getPackage(e.getKey());
                if (pkg == null) {
                    pkg = new Package(e.getKey());
                    pkg.setFootprint(e.getValue());
                    c.addPackage(pkg);
                }
                Part part = new Part("nano-" + e.getKey());
                part.setPackage(pkg);
                c.addPart(part);
            }
            c.getBoard(file);
            return null;
        }, 10_000);
        return file;
    }

    /** Pads per reason ("tip:pitch", "tip:no-plan", "part", "no-type", ...) of a check, and the pads it lists. */
    static Map<String, Integer> split(JsonObject check) {
        Map<String, Integer> m = new TreeMap<>();
        for (JsonElement e : check.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            String k = str(g, "reason") + (str(g, "tipKind") == null ? "" : ":" + str(g, "tipKind"));
            m.merge(k, g.get("pads").getAsInt(), Integer::sum);
        }
        return m;
    }

    static Set<String> padIds(JsonObject check, String reason, String tipKind) {
        Set<String> s = new TreeSet<>(GroupKey.NATURAL);
        for (JsonElement e : check.getAsJsonArray("groups")) {
            JsonObject g = e.getAsJsonObject();
            if (reason.equals(str(g, "reason")) && (tipKind == null || tipKind.equals(str(g, "tipKind")))) {
                for (JsonElement p : g.getAsJsonArray("padIds")) {
                    s.add(p.getAsString());
                }
            }
        }
        return s;
    }

    /** A board of hand-made packages: name -> pad sizes {w, h} at x ±pitch/2 (two pads "1", "2"), parts spaced 6 mm. */
    File chipBoard(String name, Object[][] packages) throws Exception {
        StringBuilder sb = new StringBuilder("<openpnp-board version=\"1.1\" name=\"" + name + "\">\n"
                + "<dimensions units=\"Millimeters\" x=\"30.0\" y=\"16.0\" z=\"0.0\" rotation=\"0.0\"/>\n"
                + "<placements>\n");
        int n = 0;
        for (Object[] p : packages) {
            for (int i = 0; i < (Integer) p[4]; i++, n++) {
                sb.append("<placement version=\"1.4\" side=\"Top\" id=\"").append(p[0]).append(i + 1)
                        .append("\" part-id=\"part-").append(p[0]).append("\" type=\"Placement\" enabled=\"true\" "
                                + "rank=\"0\">\n<location units=\"Millimeters\" x=\"").append(3 + 6 * (n % 4))
                        .append("\" y=\"").append(3 + 5 * (n / 4)).append("\" z=\"0.0\" rotation=\"0.0\"/>\n")
                        .append("<error-handling>Default</error-handling>\n</placement>\n");
            }
        }
        sb.append("</placements>\n<fiducials/>\n</openpnp-board>\n");
        File file = new File(sim.configDir, name + ".board.xml");
        Files.write(file.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        EdtRunner.call(() -> {
            Configuration c = Configuration.get();
            for (Object[] p : packages) {
                Footprint fp = new Footprint();
                fp.setUnits(LengthUnit.Millimeters);
                for (int k = 0; k < 2; k++) {
                    Footprint.Pad pad = new Footprint.Pad();
                    pad.setName(Integer.toString(k + 1));
                    pad.setX((k == 0 ? -1 : 1) * (Double) p[3] / 2);
                    pad.setWidth((Double) p[1]);
                    pad.setHeight((Double) p[2]);
                    pad.setRoundness(50);
                    fp.addPad(pad);
                }
                Package pkg = new Package((String) p[0]);
                pkg.setFootprint(fp);
                c.addPackage(pkg);
                Part part = new Part("part-" + p[0]);
                part.setPackage(pkg);
                c.addPart(part);
            }
            c.getBoard(file);
            return null;
        }, 10_000);
        return file;
    }

    JsonObject planOf(File board) throws Exception {
        return ok(call("POST", "/api/plan", "{\"source\":\"file\",\"file\":\"" + board.getCanonicalPath()
                .replace("\\", "\\\\") + "\",\"x\":120,\"y\":120,\"z\":10,\"rotation\":0}"));
    }

    String applyBody(String source, JsonObject group, String choice) {
        return "{" + source + ",\"groups\":[\"" + str(group, "group") + "\"],\"choice\":\"" + choice
                + "\",\"suggestionIds\":{\"" + str(group, "group") + "\":\"" + str(group.getAsJsonObject(
                        "suggestion"), "id") + "\"}}";
    }

    /** A fresh dose record of the current setup in this simulator (§1.7). */
    DoseRecord record(String kind, int steps) {
        DispenseConfig c = svc.getConfig();
        DoseRecord r = new DoseRecord();
        r.kind = kind;
        r.steps = steps;
        r.n = 40;
        r.pressurePsi = c.profile.pressurePsi;
        r.doseFp = c.profile.doseFingerprint();
        r.tipKey = c.tip.key;
        r.pasteKey = c.paste.key;
        r.regulatorPsi = c.profile.pressurePsi;
        r.regulatorAtMs = System.currentTimeMillis();
        r.simulated = svc.isSimulatorConfirmed();
        r.method = r.simulated ? "sim" : "manual";
        return r;
    }

    // ------------------------------------------------------------------------------------------
    // The nano split (calibration brief: about 84 pads too fine for the 22G tip, 41 dispensable)
    // ------------------------------------------------------------------------------------------

    /** The bench thread's 84 too-fine nano pads (calibration brief), by ref and pad numbers. */
    static final String[][] BENCH_84 = {{"IC1", "28"}, {"IC3", "32"}, {"J3", "5"}, {"RP1", "8"}, {"RP2", "8"},
        {"Y1", "3"}};

    @Test
    public void theNanoSplitThroughTheApi() throws Exception {
        Fixture f = pads("nano");
        JsonObject top = checkBoard(boardFile(f, "top"));
        JsonObject bottom = checkBoard(boardFile(f, "bottom"));
        Set<String> tooFine = padIds(top, "tip", "pitch");
        tooFine.addAll(padIds(bottom, "tip", "pitch"));
        Set<String> bench = new TreeSet<>(GroupKey.NATURAL);
        for (String[] b : BENCH_84) {
            for (int i = 1; i <= Integer.parseInt(b[1]); i++) {
                bench.add(b[0] + "." + i);
            }
        }
        assertEquals(84, bench.size());
        assertEquals(bench, tooFine, "the dot-gap test finds exactly the bench's too-fine pads");
        assertEquals(125, f.pads.size());
        int rest = 0;
        for (JsonObject c : new JsonObject[] {top, bottom}) {
            for (Map.Entry<String, Integer> e : split(c).entrySet()) {
                rest += e.getKey().equals("tip:pitch") ? 0 : e.getValue();
            }
        }
        // the other 41: every pad not listed has a plan; the listed ones need a profile, or are named
        int listed55 = 0;
        for (int v : split(top).values()) {
            listed55 += v;
        }
        assertEquals(55, listed55, "no top pad is covered yet: " + split(top));
        assertEquals(Map.of("tip:pitch", 40, "part", 5, "below-gate", 8, "no-type", 2), split(top));
        assertEquals(Map.of("tip:pitch", 44, "tip:no-plan", 1, "tip:near", 2, "part", 3, "below-gate", 12, "no-type", 8),
                split(bottom));
        assertEquals(41, rest, "125 - 84");
        // the DO-220AA pin: a half-turn dot reaches past the 0.61 mm pin; its tab is held back with it (decision 4)
        assertEquals(Set.of("D1.2"), padIds(bottom, "tip", "no-plan"));
        assertEquals(Set.of("D1.1", "C3.2", "C4.2"), padIds(bottom, "part", null));
        // decision 14: C3 and C4 face each other 1.27 mm apart; the other C_0603 pads keep the plan, and
        // decision 4 holds C3 and C4 whole (C3.2 and C4.2 above)
        assertEquals(Set.of("C3.1", "C4.1"), padIds(bottom, "tip", "near"));
        JsonObject near = null;
        for (JsonElement e : bottom.getAsJsonArray("groups")) {
            if ("near".equals(str(e.getAsJsonObject(), "tipKind"))) {
                near = e.getAsJsonObject();
            }
        }
        assertEquals(14, near.get("nearOf").getAsInt());
        assertEquals("2 of 14 pads are too close to a neighbour: their dots would leave 0.11 mm to the dot of C4 pad 1 "
                + "(at least 0.20 mm), so they get no paste. The other 12 keep the plan.", str(near, "reasonText"));
        assertTrue(near.get("blocking").getAsBoolean());
        assertEquals("none", str(near.getAsJsonObject("suggestion"), "kind"));
        // planned with the 0603 type characterized: the planner holds C3 and C4 back whole, the check agrees
        characterize("chip-0603");
        JsonObject plan = planOf(new File(sim.configDir, "nano-bottom.board.xml"));
        Set<String> held = new TreeSet<>(GroupKey.NATURAL);
        int c0603Dots = 0;
        for (JsonElement e : plan.getAsJsonArray("pads")) {
            JsonObject pad = e.getAsJsonObject();
            if ("C_0603_1608Metric".equals(str(pad, "packageId"))) {
                if (pad.get("blocked").getAsBoolean()) {
                    held.add(str(pad, "padId"));
                    assertTrue(pad.getAsJsonArray("flags").toString().contains("PART_NOT_DISPENSABLE"), pad.toString());
                }
                else {
                    c0603Dots += pad.get("dotCount").getAsInt();
                }
            }
        }
        assertEquals(Set.of("C3.1", "C3.2", "C4.1", "C4.2"), held);
        assertEquals(10, c0603Dots, "one half-turn dot on each of the other 10 pads");
        JsonObject planned = plan.getAsJsonObject("profileCheck");
        assertEquals(Set.of("C3.1", "C4.1"), padIds(planned, "tip", "near"));
        assertTrue(padIds(planned, "part", null).containsAll(Set.of("C3.2", "C4.2")), planned.toString());
        assertTrue(padIds(planned, "below-gate", null).isEmpty() && padIds(planned, "no-type", null).size() == 8,
                split(planned).toString());

        // pasting the partly undispensable parts' other pads anyway: the 6 parts' pads get a plan instead
        for (String pkg : new String[] {"USB_Mini-B_Lumberg_2486_01_Horizontal",
            "VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm", "DO-220AA"}) {
            JsonObject m = ok(call("POST", "/api/package-mappings", "{\"packageId\":\"" + pkg
                    + "\",\"pastePartial\":true}"));
            assertTrue(m.get("pastePartial").getAsBoolean());
            assertNull(m.get("mapping").isJsonNull() ? null : m.get("mapping"));
        }
        top = checkBoard(new File(sim.configDir, "nano-top.board.xml"));
        assertFalse(split(top).containsKey("part"), split(top).toString());
        assertEquals(3, top.getAsJsonArray("pastePartialPackages").size());
    }

    // ------------------------------------------------------------------------------------------
    // The upload preview (BI §3.2, PC §12.6)
    // ------------------------------------------------------------------------------------------

    @Test
    public void theUploadPreviewCarriesTheProfileCheck() throws Exception {
        JsonObject pv = preview(kicad(NANO), "NanoV3.3.kicad_pcb");
        int needs = 0;
        for (JsonElement e : pv.getAsJsonArray("warnings")) {
            JsonObject w = e.getAsJsonObject();
            assertFalse("NO_PAD_TYPE".equals(str(w, "code")), "NEEDS_PROFILE replaces it: " + w);
            if ("NEEDS_PROFILE".equals(str(w, "code"))) {
                needs++;
                assertEquals(1, w.get("group").getAsInt());
                assertEquals("5 packages need a profile: LED_0805_2012Metric (not calibrated yet), "
                        + "Resonator_SMD_Murata_CSTxExxV-3Pin_3.0x1.1mm (cannot be dispensed with the 22G bent taper), "
                        + "USB_Mini-B_Lumberg_2486_01_Horizontal (cannot be dispensed with the 22G bent taper), "
                        + "SW_SPST_3.9x2.9mm (no pad type), VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm (cannot be "
                        + "dispensed with the 22G bent taper). After saving, choose a suggestion for each in Profile › Needs "
                        + "a profile.", str(w, "text"));
                assertEquals(Arrays.asList("TX0", "RX0", "PWR0", "L0", "Y1", "J3", "SW1", "IC3").size(),
                        w.getAsJsonArray("refs").size());
            }
        }
        assertEquals(1, needs, "one warning for every package");
        Map<String, String> outcome = new TreeMap<>();
        Map<String, Integer> reasons = new TreeMap<>();
        for (JsonElement e : pv.getAsJsonArray("packages")) {
            JsonObject p = e.getAsJsonObject();
            outcome.put(str(p, "id"), str(p, "outcome"));
            if (p.get("profileCheck") != null && !p.get("profileCheck").isJsonNull()) {
                for (JsonElement g : p.getAsJsonArray("profileCheck")) {
                    reasons.merge(str(g.getAsJsonObject(), "reason"), 1, Integer::sum);
                    assertNotNull(g.getAsJsonObject().get("suggestion"));
                }
            }
        }
        Map<String, String> want = new TreeMap<>();
        want.put("Fiducial_1mm_Mask1.5mm", "not-dispensed");
        want.put("LED_0805_2012Metric", "needs-profile");
        want.put("Resonator_SMD_Murata_CSTxExxV-3Pin_3.0x1.1mm", "not-dispensed");
        want.put("SW_SPST_3.9x2.9mm", "needs-profile");
        want.put("USB_Mini-B_Lumberg_2486_01_Horizontal", "not-dispensed");
        want.put("VQFN-32-1EP_5x5mm_P0.5mm_EP3.254x3.254mm", "not-dispensed");
        assertEquals(want, outcome, "needs-profile replaces no-pad-type; the tip's and whole parts' packages get none");
        assertEquals(Map.of("below-gate", 1, "no-type", 1, "part", 2, "tip", 3), reasons);
        JsonObject sw = pkgOf(pv, "SW_SPST_3.9x2.9mm").getAsJsonArray("profileCheck").get(0).getAsJsonObject();
        assertEquals("no pad type", str(sw, "short"));
        assertEquals("new", str(sw.getAsJsonObject("suggestion"), "kind"));
        assertEquals("SW_SPST_3.9x2.9mm", str(sw.getAsJsonObject("suggestion").getAsJsonObject("mapping"),
                "packageId"));
        for (JsonElement e : pv.getAsJsonArray("placements")) {
            JsonObject x = e.getAsJsonObject();
            assertEquals(outcome.get(str(x, "packageId")), str(x, "outcome"), x.toString());
        }

        // the openings of the upload are the fixture's (R/*.pads.json, top side) within 0.001 mm
        assertEquals(55, sameOpenings(pv, pads("nano")));
        assertEquals(160, sameOpenings(preview(kicad("ftp.kicad_pcb.gz"), "ftp.kicad_pcb"), pads("ftp")));
        assertEquals(261, sameOpenings(ok(send("POST", "/api/boards/import?file=mobo.kicad_pcb&pasteDnp=1",
                kicad("mobo.kicad_pcb.gz"), "application/octet-stream")), pads("mobo")));
    }

    /** Every opening of the preview's placements equals the fixture's (board frame, size, roundness); their count. */
    static int sameOpenings(JsonObject pv, Fixture f) {
        Map<String, JsonObject> pkgs = new TreeMap<>();
        for (JsonElement e : pv.getAsJsonArray("packages")) {
            pkgs.put(str(e.getAsJsonObject(), "id"), e.getAsJsonObject());
        }
        Map<String, FPad> byId = new TreeMap<>();
        for (FPad fp : f.pads) {
            if (fp.side.equals("top")) {
                byId.put(fp.ref + "." + fp.padName, fp);
            }
        }
        int compared = 0;
        for (JsonElement e : pv.getAsJsonArray("placements")) {
            JsonObject x = e.getAsJsonObject();
            JsonObject pk = pkgs.get(str(x, "packageId"));
            if (pk.get("fiducial").getAsBoolean()) {
                continue;
            }
            double c = Math.cos(Math.toRadians(x.get("rotation").getAsDouble()));
            double s = Math.sin(Math.toRadians(x.get("rotation").getAsDouble()));
            for (JsonElement pe : pk.getAsJsonArray("pads")) {
                JsonObject pad = pe.getAsJsonObject();
                FPad fp = byId.get(str(x, "ref") + "." + str(pad, "name"));
                assertNotNull(fp, x + " " + pad);
                double px = pad.get("x").getAsDouble();
                double py = pad.get("y").getAsDouble();
                assertEquals(fp.x, x.get("x").getAsDouble() + px * c - py * s, 0.001, fp.padId);
                assertEquals(fp.y, x.get("y").getAsDouble() + px * s + py * c, 0.001, fp.padId);
                // mobo J5's two paste polygons carry a 0.12 mm outline: K/ counts it (the plotted window), the
                // fixture's extractor does not (R/README.md)
                double stroke = fp.padId.equals("J5.6~1") || fp.padId.equals("J5.7~1") ? 0.12 : 0;
                assertEquals(fp.wMm + stroke, pad.get("w").getAsDouble(), 0.001, fp.padId);
                assertEquals(fp.hMm + stroke, pad.get("h").getAsDouble(), 0.001, fp.padId);
                assertEquals(stroke > 0 ? 100 * 0.06 / ((fp.wMm + stroke) / 2) : fp.roundness,
                        pad.get("roundness").getAsDouble(), 0.001, fp.padId);
                compared++;
            }
        }
        return compared;
    }

    // ------------------------------------------------------------------------------------------
    // Use, Save, stale suggestions (§12.1)
    // ------------------------------------------------------------------------------------------

    static final Object[][] CHIPS = {{"TestChip_A", 0.80, 0.95, 1.65, 3}, {"TestStrip_B", 1.50, 0.62, 2.40, 2}};

    @Test
    public void useAnExistingTypeAndSaveANewOne() throws Exception {
        characterize("chip-0603");
        File board = chipBoard("chips", CHIPS);
        JsonObject plan = planOf(board);
        String planId = str(plan, "planId");
        JsonObject pc = plan.getAsJsonObject("profileCheck");
        assertFalse(pc.get("ok").getAsBoolean());
        assertEquals(2, pc.get("fixable").getAsInt(), pc.toString());
        JsonObject a = group(pc, "TestChip_A");
        assertEquals("no-type", str(a, "reason"));
        assertTrue(a.get("blocking").getAsBoolean() && a.get("fixable").getAsBoolean());
        JsonObject use = a.getAsJsonObject("suggestion");
        assertEquals("use", str(use, "kind"));
        assertEquals("chip-0603", str(use, "typeKey"));
        assertEquals("Use 0603: same pad (0.80 × 0.95 mm; these are 0.80 × 0.95 mm). 1 half-turn dot per pad, 1.06 mg, "
                + "dots 1.16 mm wide.", str(use, "sentence"));
        // the same groups and suggestions from the check of the plan and of the board file before planning
        JsonObject byPlan = check("planId=" + planId);
        assertEquals(str(use, "id"), str(group(byPlan, "TestChip_A").getAsJsonObject("suggestion"), "id"));
        assertEquals(planId, str(byPlan.getAsJsonObject("source"), "planId"));
        JsonObject byFile = checkBoard(board);
        assertEquals(str(a, "group"), str(group(byFile, "TestChip_A"), "group"));
        assertEquals(board.getCanonicalPath(), new File(str(byFile.getAsJsonObject("source"), "boardFile"))
                .getCanonicalPath());
        refused(get("/api/pad-profile/check?planId=nope"), 404, "not-found");
        refused(get("/api/pad-profile/check?boardFile=" + enc(new File(sim.configDir, "x.board.xml").getPath())), 409,
                "refused");
        refused(get("/api/pad-profile/check"), 400, "bad-request");

        // a suggestion id that is not the current one: nothing is written, the new check comes back
        String src = "\"planId\":\"" + planId + "\"";
        JsonObject stale = refused(call("POST", "/api/pad-profile/apply", "{" + src + ",\"groups\":[\""
                + str(a, "group") + "\"],\"choice\":\"use\",\"suggestionIds\":{\"" + str(a, "group")
                + "\":\"s000000000000\"}}"), 409, "suggestion-stale");
        assertNotNull(stale.getAsJsonObject("profileCheck").getAsJsonArray("groups"));
        refused(call("POST", "/api/pad-profile/apply", "{" + src + ",\"groups\":[\"g000000000000\"],\"choice\":"
                + "\"use\"}"), 409, "suggestion-stale");
        refused(call("POST", "/api/pad-profile/apply", "{" + src + ",\"groups\":\"all\",\"choice\":\"maybe\"}"),
                400, "bad-request");
        assertEquals(0, get("/api/package-mappings").array().size(), "nothing written");

        // Use: the mapping, then the board planned again; the group is gone
        JsonObject b = group(pc, "TestStrip_B");
        JsonObject usedA = ok(call("POST", "/api/pad-profile/apply", "{" + src + ",\"groups\":[\"" + str(a, "group")
                + "\",\"" + str(b, "group") + "\"],\"choice\":\"use\",\"suggestionIds\":{\"" + str(a, "group") + "\":\""
                + str(use, "id") + "\",\"" + str(b, "group") + "\":\"" + str(b.getAsJsonObject("suggestion"), "id")
                + "\"}}"));
        JsonObject applied = usedA.getAsJsonArray("applied").get(0).getAsJsonObject();
        assertEquals("use", str(applied, "kind"));
        assertEquals("chip-0603", str(applied, "typeKey"));
        assertEquals("TestChip_A", str(applied.getAsJsonObject("mapping"), "packageId"));
        assertTrue(applied.getAsJsonObject("mapping").get("padNames").isJsonNull());
        JsonObject skipped = usedA.getAsJsonArray("skipped").get(0).getAsJsonObject();
        assertEquals(str(b, "group"), str(skipped, "group"));
        assertTrue(str(skipped, "why").startsWith("no pad type of this size passes the gate yet"), skipped.toString());
        String newPlanId = str(usedA, "newPlanId");
        assertNotNull(newPlanId);
        assertFalse(usedA.get("planStale").getAsBoolean());
        JsonObject again = ok(get("/api/plan/" + newPlanId));
        assertNull(group(again.getAsJsonObject("profileCheck"), "TestChip_A"));
        for (JsonElement e : again.getAsJsonArray("pads")) {
            JsonObject pad = e.getAsJsonObject();
            if ("TestChip_A".equals(str(pad, "packageId"))) {
                assertEquals("chip-0603", str(pad, "typeKey"));
                assertEquals("package-map", str(pad.getAsJsonObject("typeRule"), "kind"));
                assertFalse(pad.get("blocked").getAsBoolean());
            }
        }
        JsonArray maps = get("/api/package-mappings").array();
        assertEquals(1, maps.size());
        assertEquals("TestChip_A", str(maps.get(0).getAsJsonObject(), "packageId"));
        assertEquals("chip-0603", str(maps.get(0).getAsJsonObject(), "typeKey"));

        // Save: a new provisional pad type with the suggested dot plan, mapped to its package
        JsonObject b2 = group(again.getAsJsonObject("profileCheck"), "TestStrip_B");
        JsonObject sugg = b2.getAsJsonObject("suggestion");
        assertEquals("new", str(sugg, "kind"));
        JsonObject saved = ok(call("POST", "/api/pad-profile/apply", applyBody("\"planId\":\"" + newPlanId + "\"", b2,
                "save")));
        JsonObject t = saved.getAsJsonArray("applied").get(0).getAsJsonObject();
        assertEquals("new", str(t, "kind"));
        assertEquals(str(sugg, "typeKey"), str(t, "typeKey"));
        assertEquals(1, t.get("version").getAsInt());
        JsonObject type = ok(get("/api/pad-types/" + str(t, "typeKey"))).getAsJsonObject("current");
        assertEquals("provisional", str(type, "status"));
        assertEquals("suggested", str(type, "origin"));
        assertEquals("footprint-only", str(type, "layout"));
        assertEquals(sugg.getAsJsonObject("dots").get("steps").getAsInt(), type.get("dotSteps").getAsInt());
        assertEquals(sugg.getAsJsonObject("dots").get("perPad").getAsInt(), type.get("dotsPerPad").getAsInt());
        assertEquals("ad22tta-b", str(type, "tipKey"));
        assertEquals(sugg.getAsJsonObject("opening").get("areaMm2").getAsDouble(), type.get("calAreaMm2").getAsDouble(),
                1e-9);
        assertTrue(str(type, "notes").startsWith(str(sugg, "sentence")), str(type, "notes"));
        assertTrue(type.get("doseFp").isJsonNull());
        assertEquals(2, get("/api/package-mappings").array().size());
        // planned again with the provisional type: below the gate, to calibrate with its own plan
        JsonObject third = ok(get("/api/plan/" + str(saved, "newPlanId")));
        JsonObject b3 = group(third.getAsJsonObject("profileCheck"), "TestStrip_B");
        assertEquals("below-gate", str(b3, "reason"));
        assertEquals("calibrate-existing", str(b3.getAsJsonObject("suggestion"), "kind"));
        assertEquals("your plan", str(b3.getAsJsonObject("suggestion"), "planFrom"));
        for (JsonElement e : third.getAsJsonArray("pads")) {
            JsonObject pad = e.getAsJsonObject();
            if ("TestStrip_B".equals(str(pad, "packageId"))) {
                assertEquals(type.get("dotSteps").getAsInt(), pad.get("dotSteps").getAsInt());
                assertEquals(str(b3, "group"), str(pad, "profileGroup"));
                assertTrue(pad.get("blocked").getAsBoolean(), "below the gate");
            }
        }
        // "all" applies only what the choice covers; nothing left to use here
        JsonObject all = ok(call("POST", "/api/pad-profile/apply", "{\"planId\":\"" + str(saved, "newPlanId")
                + "\",\"groups\":\"all\",\"choice\":\"use\"}"));
        assertEquals(0, all.getAsJsonArray("applied").size());
        assertTrue(all.get("newPlanId").isJsonNull());
        assertFalse(all.get("planStale").getAsBoolean());
    }

    /**
     * Tip setups (§22.2, §22.6): with a tip whose dots were never measured mounted, the check names no tip or part
     * reason it cannot know, asks to measure its dots in every group below the gate, writes null where a size is not
     * known (never a NaN token), and names the setup that has every pad type of the board; a suggestion shown before a
     * mount is stale after it.
     */
    @Test
    public void theCheckOfATipNeverMeasuredAsksForItsDots() throws Exception {
        characterize("chip-0603");
        File board = chipBoard("chips", new Object[][] {{"TestChip_A", 0.80, 0.95, 1.65, 3}});
        ok(call("POST", "/api/package-mappings", "{\"packageId\":\"TestChip_A\",\"typeKey\":\"chip-0603\"}"));
        JsonObject plan = planOf(board);
        assertTrue(plan.getAsJsonObject("profileCheck").get("ok").getAsBoolean(), plan.toString());
        Res added = call("POST", "/api/tip-setups", "{\"name\":\"25G straight\",\"gauge\":25,\"idMm\":0.25,"
                + "\"odMm\":0.51,\"form\":\"straight\",\"taper\":\"straight-steel\"}");
        assertEquals(201, added.status, added.toString());
        ok(call("POST", "/api/tip-setups/25g-straight/mount", "{}"));

        Res raw = get("/api/pad-profile/check?boardFile=" + enc(board.getCanonicalPath()));
        assertFalse(raw.body.contains("NaN") || raw.body.contains("Infinity"), "JSON.parse would refuse it: " + raw);
        JsonObject pc = raw.json();
        JsonObject tip = pc.getAsJsonObject("tip");
        assertEquals("25g-straight", str(tip, "key"));
        assertEquals("none", str(tip, "dots"));
        assertTrue(tip.get("minDotMm").isJsonNull(), tip.toString());
        assertEquals("Vieweg DV-5005", str(tip, "dispenser"));
        JsonObject g = group(pc, "TestChip_A");
        assertEquals("below-gate", str(g, "reason"), g.toString());
        assertTrue(g.getAsJsonObject("type").get("standIn").getAsBoolean(), g.toString());
        JsonObject s = g.getAsJsonObject("suggestion");
        assertEquals("measure-tip", str(s, "kind"), s.toString());
        assertEquals("Measure the 25G straight's dots first (Setup › Dispenser & tip › Measure its dots, ≈ 8 min and "
                + "two coupons to weigh): suggestions need its dot sizes and paste per turn.", str(s, "sentence"));
        JsonArray others = pc.getAsJsonArray("otherTips");
        assertEquals(1, others.size(), pc.toString());
        assertEquals("ad22tta-b", str(others.get(0).getAsJsonObject(), "key"));
        assertEquals(1, others.get(0).getAsJsonObject().get("of").getAsInt());
        // the plan made with the 22G: its pads' dots and masses null where the 25G does not know them
        JsonObject view = ok(get("/api/plan/" + str(plan, "planId")));
        assertFalse(view.toString().contains("NaN"), view.toString());
    }

    @Test
    public void nothingIsWrittenUntilEveryIdIsCurrentAndCalibrateExistingSaves() throws Exception {
        characterize("chip-0603");
        File board = chipBoard("mixed", new Object[][] {{"TestChip_A", 0.80, 0.95, 1.65, 2},
            {"R_0805_2012Metric", 1.025, 1.40, 1.825, 2}});
        JsonObject plan = planOf(board);
        String planId = str(plan, "planId");
        String src = "\"planId\":\"" + planId + "\"";
        JsonObject pc = plan.getAsJsonObject("profileCheck");
        JsonObject a = group(pc, "TestChip_A");
        JsonObject r = group(pc, "R_0805_2012Metric");
        assertEquals("use", str(a.getAsJsonObject("suggestion"), "kind"));
        assertEquals("below-gate", str(r, "reason"), r.toString());
        JsonObject cal = r.getAsJsonObject("suggestion");
        assertEquals("calibrate-existing", str(cal, "kind"), cal.toString());
        assertEquals("chip-0805", str(cal, "typeKey"));
        assertEquals("suggested", str(cal, "planFrom"));

        // one of two ids is not current: nothing is written, not even the group whose id is
        refused(call("POST", "/api/pad-profile/apply", "{" + src + ",\"groups\":[\"" + str(a, "group") + "\",\""
                + str(r, "group") + "\"],\"choice\":\"save\",\"suggestionIds\":{\"" + str(a, "group") + "\":\""
                + str(a.getAsJsonObject("suggestion"), "id") + "\",\"" + str(r, "group") + "\":\"s000000000000\"}}"),
                409, "suggestion-stale");
        assertEquals(0, get("/api/package-mappings").array().size(), "the use group's mapping is not written");
        assertEquals(1, ok(get("/api/pad-types/chip-0805")).getAsJsonObject("current").get("version").getAsInt());

        // a pad type added since the check was shown: the plan's check is computed again and the shown id is stale
        ok(call("POST", "/api/pad-types", "{\"forSetup\":\"ad22tta-b\",\"name\":\"Other\",\"family\":\"Custom\",\"padWMm\":3,\"padHMm\":3,"
                + "\"layout\":\"single\"}"));
        JsonObject again = check("planId=" + planId);
        JsonObject r2 = group(again, "R_0805_2012Metric");
        assertFalse(str(r2.getAsJsonObject("suggestion"), "id").equals(str(cal, "id")), "the types changed");
        assertFalse(str(group(planCheck(planId), "R_0805_2012Metric").getAsJsonObject("suggestion"), "id")
                .equals(str(cal, "id")), "the plan view's check is not kept from before the change");
        refused(call("POST", "/api/pad-profile/apply", applyBody(src, r, "save")), 409, "suggestion-stale");

        // Use on a calibrate-existing suggestion: skipped, with what to do instead
        JsonObject use = ok(call("POST", "/api/pad-profile/apply", applyBody(src, r2, "use")));
        assertEquals(0, use.getAsJsonArray("applied").size());
        assertEquals("0805 is not calibrated for these pads yet: save the suggestion as a provisional version of it, "
                + "then calibrate it", str(use.getAsJsonArray("skipped").get(0).getAsJsonObject(), "why"));

        // Save: a new provisional version of chip-0805 with the suggested plan; its pad and pattern stay
        JsonObject saved = ok(call("POST", "/api/pad-profile/apply", applyBody(src, r2, "save")));
        JsonObject t = saved.getAsJsonArray("applied").get(0).getAsJsonObject();
        assertEquals("calibrate-existing", str(t, "kind"));
        assertEquals("chip-0805", str(t, "typeKey"));
        assertEquals(2, t.get("version").getAsInt());
        assertTrue(t.get("mapping").isJsonNull(), "the pattern already resolves these pads");
        JsonObject type = ok(get("/api/pad-types/chip-0805")).getAsJsonObject("current");
        JsonObject dots = r2.getAsJsonObject("suggestion").getAsJsonObject("dots");
        assertEquals("provisional", str(type, "status"));
        assertEquals(dots.get("steps").getAsInt(), type.get("dotSteps").getAsInt());
        assertEquals(dots.get("perPad").getAsInt(), type.get("dotsPerPad").getAsInt());
        assertEquals("ad22tta-b", str(type, "tipKey"));
        assertEquals(r2.getAsJsonObject("suggestion").getAsJsonObject("opening").get("areaMm2").getAsDouble(),
                type.get("calAreaMm2").getAsDouble(), 1e-9);
        assertEquals("suggested", str(type, "origin"));
        assertEquals(1.025, type.get("padWMm").getAsDouble(), 0);
        assertEquals(1.40, type.get("padHMm").getAsDouble(), 0);
        assertNotNull(str(type, "packagePattern"));
        assertEquals(0, get("/api/package-mappings").array().size());

        // planned again with the type's own plan: saving the same plan again writes nothing
        String next = "\"planId\":\"" + str(saved, "newPlanId") + "\"";
        JsonObject r3 = group(planCheck(str(saved, "newPlanId")), "R_0805_2012Metric");
        assertEquals("your plan", str(r3.getAsJsonObject("suggestion"), "planFrom"));
        JsonObject same = ok(call("POST", "/api/pad-profile/apply", applyBody(next, r3, "save")));
        assertEquals(0, same.getAsJsonArray("applied").size());
        assertEquals("0805 already has this plan: calibrate it", str(same.getAsJsonArray("skipped").get(0)
                .getAsJsonObject(), "why"));
        assertTrue(same.get("newPlanId").isJsonNull());
        assertFalse(same.get("planStale").getAsBoolean());
        assertEquals(2, ok(get("/api/pad-types/chip-0805")).getAsJsonObject("current").get("version").getAsInt());
    }

    /** The profile check of a plan's view (GET /api/plan/{id}). */
    JsonObject planCheck(String planId) throws Exception {
        return ok(get("/api/plan/" + planId)).getAsJsonObject("profileCheck");
    }

    // ------------------------------------------------------------------------------------------
    // Mappings, paste, the calibration area
    // ------------------------------------------------------------------------------------------

    @Test
    public void mappingsPasteAndTheCalibrationArea() throws Exception {
        refused(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\",\"typeKey\":\"nope\"}"), 400,
                "bad-request");
        refused(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\",\"padNames\":[\"1,2\"],"
                + "\"typeKey\":\"chip-0805\"}"), 400, "bad-request");
        refused(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\"}"), 400, "bad-request");
        refused(call("POST", "/api/package-mappings", "{\"typeKey\":\"chip-0805\"}"), 400, "bad-request");
        String fp0 = svc.padTypeResolver().fingerprint();
        JsonObject m = ok(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\",\"padNames\":[\"3\","
                + "\"1\",\"2\"],\"typeKey\":\"chip-0805\"}"));
        assertEquals(Arrays.asList("1", "2", "3"), new Gson().fromJson(m.getAsJsonObject("mapping").get("padNames"),
                List.class));
        assertEquals(svc.padTypeResolver().fingerprint(), str(m, "padTypesFingerprint"));
        assertFalse(fp0.equals(str(m, "padTypesFingerprint")), "plans made before are stale");
        assertEquals("Plans made before this are stale: plan again.", str(m, "note"));
        JsonObject row = get("/api/package-mappings").array().get(0).getAsJsonObject();
        assertEquals("SOT-223", str(row, "packageId"));
        assertEquals(3, row.getAsJsonArray("padNames").size());
        assertEquals("chip-0805", str(row, "typeKey"));
        assertTrue(row.get("version").getAsInt() >= 1 && row.get("atMs").getAsLong() > 0);
        ok(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\",\"padNames\":[\"1\",\"2\",\"3\"],"
                + "\"typeKey\":null}"));
        assertEquals(0, get("/api/package-mappings").array().size(), "cleared");
        JsonObject opt = ok(call("POST", "/api/package-mappings", "{\"packageId\":\"SOT-223\",\"pastePartial\":true}"));
        assertTrue(opt.get("pastePartial").getAsBoolean());
        assertTrue(opt.get("mapping").isJsonNull());
        assertEquals(java.util.Collections.singleton("SOT-223"), svc.getDb().pastePartialPackages());

        // New paste loaded: stamped by the engine
        long t0 = System.currentTimeMillis();
        JsonObject paste = ok(call("POST", "/api/paste", "{\"lot\":\"L2026-10\"}"));
        assertEquals("gc50-t5", str(paste, "key"));
        assertEquals("L2026-10", str(paste, "lot"));
        assertTrue(paste.get("loadedAtMs").getAsLong() >= t0);
        refused(call("PUT", "/api/config", "{\"paste\":{\"loadedAtMs\":5}}"), 400, "bad-request");
        refused(call("POST", "/api/paste", "{\"key\":\"Not A Key\"}"), 400, "bad-request");
        ok(call("PUT", "/api/config", "{\"paste\":{\"name\":\"GC50 T5, the new jar\"}}"));
        // the tip is the mounted tip setup (§22.1): its OD is a plain edit of the setup, which refreshes cfg.tip
        refused(call("PUT", "/api/config", "{\"tip\":{\"odMm\":0.70}}"), 400, "bad-request");
        ok(call("PUT", "/api/tip-setups/ad22tta-b", "{\"odMm\":0.70}"));
        assertEquals(0.70, svc.getConfig().tip.odMm, 0);
        assertEquals(paste.get("loadedAtMs").getAsLong(), (long) svc.getConfig().paste.loadedAtMs, "kept");

        // the calibration area: default at the blank place, set, off the purge row, wiped
        JsonObject area = ok(get("/api/calibration-area"));
        assertFalse(area.get("defined").getAsBoolean());
        assertTrue(area.get("effective").isJsonNull(), "no blank place yet");
        ok(call("PUT", "/api/locations/blank", "{\"x\":90,\"y\":40,\"z\":5.5}"));
        ok(call("PUT", "/api/config", "{\"purgeRow\":{\"x0Mm\":80,\"y0Mm\":47,\"pitchXMm\":2.5,\"spots\":48}}"));
        JsonObject eff = ok(get("/api/calibration-area")).getAsJsonObject("effective");
        assertEquals(40, eff.get("widthMm").getAsDouble(), 0);
        JsonObject over = refused(call("PUT", "/api/calibration-area", "{\"x0Mm\":90,\"y0Mm\":40,\"widthMm\":40,"
                + "\"heightMm\":20}"), 400, "bad-request");
        assertTrue(str(over, "reason").startsWith("calibrationArea must stay 1.5 mm from every purge spot"), over.toString());
        JsonObject set = ok(call("PUT", "/api/calibration-area", "{\"x0Mm\":90,\"y0Mm\":10,\"widthMm\":40,"
                + "\"heightMm\":20}"));
        assertTrue(set.get("defined").getAsBoolean());
        assertEquals(20, set.get("leftMm").getAsDouble(), 0);
        refused(call("PUT", "/api/config", "{\"calibrationArea\":{\"usedMm\":3}}"), 400, "bad-request");
        DispenseConfig c = svc.getConfig();
        c.calibrationArea.reserve(8);
        svc.updateConfig(c);
        assertEquals(12, ok(get("/api/calibration-area")).get("leftMm").getAsDouble(), 1e-9);
        JsonObject wiped = ok(call("POST", "/api/calibration-area/reset", null));
        assertEquals(0, wiped.get("usedMm").getAsDouble(), 0);
        assertTrue(wiped.get("resetAtMs").getAsLong() >= t0);
        assertEquals(20, wiped.get("leftMm").getAsDouble(), 0);
        JsonObject cleared = ok(call("PUT", "/api/calibration-area", "{}"));
        assertFalse(cleared.get("defined").getAsBoolean(), "an empty area is the default again");
    }

    // ------------------------------------------------------------------------------------------
    // Pad types: own dose, status evidence, hand edits (§5.1, §12.7, decision 17)
    // ------------------------------------------------------------------------------------------

    @Test
    public void ownDoseStatusEvidenceAndHandEdits() throws Exception {
        String body = "{\"forSetup\":\"ad22tta-b\",\"name\":\"0805 one turn\",\"family\":\"Chip (2-terminal SMD)\",\"padWMm\":1.025,"
                + "\"padHMm\":1.40,\"layout\":\"two-terminal\",\"pitchMm\":1.825";
        refused(call("POST", "/api/pad-types", body + ",\"dotSteps\":533}"), 400, "bad-request");
        JsonObject r6 = refused(call("POST", "/api/pad-types", body + ",\"dotSteps\":400,\"dotsPerPad\":1}"), 400,
                "bad-request");
        assertTrue(str(r6, "reason").contains("half a screw turn per pad"), r6.toString());
        JsonObject created = ok(call("POST", "/api/pad-types", body + ",\"dotSteps\":1600,\"dotsPerPad\":1}"))
                .getAsJsonObject("padType");
        String key = str(created, "typeKey");
        assertEquals(1600, created.get("dotSteps").getAsInt());
        assertEquals("one turn", str(created, "turn"));
        assertEquals("manual", str(created, "origin"));
        assertEquals("ad22tta-b", str(created, "tipKey"), "the tip the dose was set for");

        JsonObject ev = refused(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}"), 409,
                "status-evidence");
        assertEquals("0805 one turn uses one-turn dots, which have no fresh camera check on the blank and no confirmed "
                + "weighing at this recipe: run the ladder and Weigh dots (Calibrate) first.", str(ev, "reason"));
        refused(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"validated\"}"), 409, "status-evidence");
        // a fresh passing camera record and two weighings that agree
        DoseRecord optical = record(DoseRecord.OPTICAL, 1600);
        optical.pass = true;
        optical.diameterMeanMm = 1.30;
        optical.diameterP95Mm = 1.40;
        svc.getDb().insertDoseRecord(optical);
        ev = refused(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}"), 409, "status-evidence");
        assertTrue(str(ev, "reason").contains("which have no confirmed weighing at this recipe"), ev.toString());
        long first = 0;
        for (int k = 0; k < 2; k++) {
            DoseRecord coupon = record(DoseRecord.COUPON, 1600);
            coupon.couponPlace = "coupon" + (k + 1);
            coupon = svc.getDb().insertDoseRecord(coupon);
            DoseRecord mass = record(DoseRecord.MASS, 1600);
            mass.couponRecordId = coupon.id;
            mass.fullMg = 4170.0 + 70;
            mass.emptyMg = 4170.0;
            mass.totalMg = 70.0;
            mass.mgPerDot = 2.0;
            mass.mgPerStep = 2.0 / 1600;
            mass.confirmedBy = k == 0 ? null : first;
            first = svc.getDb().insertDoseRecord(mass).id;
        }
        JsonObject ch = ok(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}"))
                .getAsJsonObject("padType");
        assertEquals("characterized", str(ch, "status"));
        assertEquals("validated", str(ok(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"status\":\"validated\"}"))
                .getAsJsonObject("padType"), "status"), "its previous version was characterized with this dose");

        // the list row: the type's own dots and turn, its calibration fields, the newest pad calibration
        JsonObject row = null;
        for (JsonElement f : ok(get("/api/pad-types")).getAsJsonArray("families")) {
            for (JsonElement t : f.getAsJsonObject().getAsJsonArray("types")) {
                if (key.equals(str(t.getAsJsonObject(), "typeKey"))) {
                    row = t.getAsJsonObject();
                }
            }
        }
        assertNotNull(row);
        assertEquals(1, row.get("dotsPerPad").getAsInt());
        assertEquals(1600 / svc.getConfig().bStepsPerDeg, row.get("rotationPerDotDeg").getAsDouble(), 1e-9);
        assertTrue(row.has("calAreaMm2") && row.has("massPerPadMg") && row.has("lastCalibration"), row.toString());
        // the status raised by hand is a calibration record of its own (§22.5): method by-hand, outcome validated
        JsonObject last = row.getAsJsonObject("lastCalibration");
        assertEquals("by-hand", str(last, "method"), last.toString());
        assertEquals("validated", str(last, "outcome"));
        assertEquals("ad22tta-b", str(last, "setupKey"));

        // a hand edit of the pad or the dose drops the calibration it had (decision 17); notes keep it
        org.openpnp.instancelabs.dispense.PadType cal = svc.getDb().currentPadType(key, Tip.SEED_KEY).copy();
        cal.doseFp = svc.getConfig().profile.doseFingerprint();
        cal.massPerPadMg = 2.0;
        cal.origin = org.openpnp.instancelabs.dispense.PadType.ORIGIN_AUTO_CALIBRATED;
        svc.savePadType(cal, Tip.SEED_KEY, cal.padVersion);
        JsonObject notes = ok(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"notes\":\"after reflow: fine\"}"))
                .getAsJsonObject("padType");
        assertEquals("auto-calibrated", str(notes, "origin"));
        assertEquals(2.0, notes.get("massPerPadMg").getAsDouble(), 0);
        JsonObject edited = ok(call("PUT", "/api/pad-types/" + key, "{\"forSetup\":\"ad22tta-b\",\"padWMm\":1.10}")).getAsJsonObject("padType");
        assertEquals("manual", str(edited, "origin"));
        assertTrue(edited.get("doseFp").isJsonNull());
        assertTrue(edited.get("massPerPadMg").isJsonNull());
    }

    // ------------------------------------------------------------------------------------------
    // SIM-only floors, refusals while a task runs, the plan view (§5.4, §12.1, §12.5)
    // ------------------------------------------------------------------------------------------

    @Test
    public void theShortenedStatisticsAreForTheSimulatorOnly() throws Exception {
        SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:GcodeServer"));
        try {
            ok(call("PUT", "/api/config", "{\"padCalibration\":{\"minHops\":10}}"));
            SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:Marlin"));
            JsonObject real = refused(call("PUT", "/api/config", "{\"padCalibration\":{\"minLadderDots\":10}}"), 400,
                    "bad-request");
            assertEquals("padCalibration.minLadderDots below 28 is allowed in the simulator only", str(real, "reason"));
            refused(call("PUT", "/api/config", "{\"padCalibration\":{\"weighTargetMg\":20}}"), 400, "bad-request");
            // what the simulator set before is no new problem: other settings still save
            ok(call("PUT", "/api/config", "{\"padCalibration\":{\"maxResidCv\":0.12}}"));
            assertEquals(10, svc.getConfig().padCalibration.minHops);
        }
        finally {
            SimGuard.noteIdentity(sim.machine, List.of("FIRMWARE_NAME:GcodeServer"));
        }
    }

    /** The board-run set-up of BoardImportSimTest, on this test's machine and server. */
    BoardImportSimTest runs() throws Exception {
        BoardImportSimTest b = new BoardImportSimTest();
        b.sim = sim;
        b.svc = svc;
        b.api = api;
        b.http = http;
        b.setUpBoardRuns();
        return b;
    }

    @Test
    public void thePlanViewAndTheRefusalsWhileATaskRuns() throws Exception {
        BoardImportSimTest b = runs();
        characterize("chip-0603");
        double[][] pos = new double[24][];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = new double[] {2 + 4 * (i % 6), 2 + 3.5 * (i / 6), 0};
        }
        JsonObject plan = b.planTied(b.writeBoard(pos));
        assertTrue(plan.getAsJsonObject("interlocks").get("ok").getAsBoolean(), plan.toString());
        JsonObject pc = plan.getAsJsonObject("profileCheck");
        assertTrue(pc.get("ok").getAsBoolean(), pc.toString());
        assertEquals(0, pc.getAsJsonArray("groups").size());
        assertEquals("ad22tta-b", str(pc.getAsJsonObject("tip"), "key"));
        JsonObject pad = plan.getAsJsonArray("pads").get(0).getAsJsonObject();
        assertTrue(str(pad, "profileGroup").matches("g[0-9a-f]{12}"), pad.toString());
        assertEquals(800, pad.get("dotSteps").getAsInt());
        assertEquals(1, pad.get("dotsPerPad").getAsInt());
        assertTrue(pad.has("startPhase"));
        assertEquals(1.055, pad.get("predictedMassMg").getAsDouble(), 0.01, "a half turn by the model");
        assertTrue(pad.get("anchorMassMg").getAsDouble() > 1.0, pad.toString());
        assertEquals(2, pad.getAsJsonArray("dotDiameterMm").size());
        assertEquals(1.16, pad.getAsJsonArray("dotDiameterMm").get(1).getAsDouble(), 0.005);
        assertTrue(pad.get("clearanceMm").getAsDouble() > 0.2, pad.toString());
        assertEquals(2, pad.getAsJsonArray("padRipplePct").size());
        JsonObject dot = plan.getAsJsonArray("dots").get(0).getAsJsonObject();
        assertEquals(800, dot.get("netSteps").getAsInt());
        assertEquals(0, dot.get("block").getAsInt());
        assertTrue(dot.get("padIndex").getAsInt() >= 0);
        assertEquals(1.16, dot.get("diameterMm").getAsDouble(), 0.005);
        JsonObject block = plan.getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals(800, block.get("netSteps").getAsInt());
        assertEquals(48, block.get("dots").getAsInt());
        assertTrue(block.get("demandMgS").getAsDouble() > 0, "filled by the estimate");

        // a run of some pads (a subset of the plan), slow: every dwell waits
        JsonArray some = new JsonArray();
        for (int i = 0; i < 20; i++) {
            some.add(plan.getAsJsonArray("pads").get(i).getAsJsonObject().get("padId"));
        }
        sim.server.delayBeforeOkMs.put("G4", 60L);
        String subsetId;
        try {
            JsonObject run = ok(call("POST", "/api/run", "{\"planId\":\"" + str(plan, "planId") + "\",\"padIds\":"
                    + some + "}"));
            subsetId = str(run, "planId");
            assertFalse(subsetId.equals(str(plan, "planId")), run.toString());
            long t0 = System.currentTimeMillis();
            while (!svc.isBusy() && System.currentTimeMillis() - t0 < 10_000) {
                Thread.sleep(10);
            }
            assertTrue(svc.isBusy());
            String[][] refused = {{"POST", "/api/pad-profile/apply", "{\"planId\":\"" + str(plan, "planId")
                    + "\",\"groups\":\"all\",\"choice\":\"save\"}"},
                {"POST", "/api/package-mappings", "{\"packageId\":\"X\",\"typeKey\":null}"},
                {"POST", "/api/paste", "{}"}, {"PUT", "/api/calibration-area", "{}"},
                {"POST", "/api/calibration-area/reset", null}};
            for (String[] r : refused) {
                JsonObject o = refused(call(r[0], r[1], r[2]), 409, "refused");
                assertEquals("a dispense task is already running; wait for it or press STOP", str(o, "reason"), r[1]);
            }
            assertEquals(200, get("/api/pad-profile/check?planId=" + str(plan, "planId")).status, "reading is fine");
        }
        finally {
            ok(call("POST", "/api/stop", null));
            sim.server.delayBeforeOkMs.clear();
            long t0 = System.currentTimeMillis();
            while (svc.isBusy() && System.currentTimeMillis() - t0 < 30_000) {
                Thread.sleep(20);
            }
        }
        // the subset's dots keep their dose (what the stream turned)
        JsonObject subset = ok(get("/api/plan/" + subsetId));
        assertEquals(20, subset.getAsJsonArray("dots").size());
        for (JsonElement e : subset.getAsJsonArray("dots")) {
            assertEquals(800, e.getAsJsonObject().get("netSteps").getAsInt(), e.toString());
            assertEquals(1.16, e.getAsJsonObject().get("diameterMm").getAsDouble(), 0.005);
        }
    }
}
