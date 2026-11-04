package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BR-73: the DNS-rebinding and CSRF guards with trusted origins, for the real machine's UI through `tailscale serve`
 * (https://NAME:PORT) proxied to the API on 127.0.0.1. Measured on the lab computer 11-03 15:45 (Infra) and 15:50: the
 * proxy forwards the browser's own Host ("NAME:8877") and Origin ("https://NAME:8877").
 */
public class ApiBrowserSafetyTest {
    static final String TAILNET = "https://lumen-thinkpad.tail3e978a.ts.net:8877";
    static final String HOST = "lumen-thinkpad.tail3e978a.ts.net:8877";

    @TempDir
    File dir;

    @AfterEach
    public void clearOverride() {
        System.clearProperty("instancelabs.api.trustedOrigins");
    }

    static Set<String> trusted(String... origins) {
        Set<String> s = new LinkedHashSet<>();
        for (String o : origins) {
            s.add(ApiSettings.normalizeOrigin(o));
        }
        return s;
    }

    static String refusal(String method, String host, String origin, String site, Set<String> trusted) {
        return ApiServer.browserSafetyRefusal(method, host, origin, site, false, trusted);
    }

    @Test
    public void loopbackAloneAsBefore() {
        Set<String> none = Collections.emptySet();
        assertNull(refusal("GET", "127.0.0.1:18877", null, null, none));
        assertNull(refusal("POST", "127.0.0.1:18877", "http://127.0.0.1:18877", "same-origin", none));
        assertNull(refusal("POST", "localhost:18877", null, null, none), "curl without Origin");
        assertNotNull(refusal("POST", "127.0.0.1:18877", "http://127.0.0.1:9999", null, none));
        // without the setting the tailnet name is refused, GET included (DNS-rebinding guard)
        assertNotNull(refusal("GET", HOST, null, null, none));
        assertNotNull(refusal("POST", "127.0.0.1:18877", TAILNET, "same-origin", none));
    }

    @Test
    public void theTrustedTailnetOriginPassesBothGuards() {
        Set<String> t = trusted(TAILNET);
        assertNull(refusal("GET", HOST, null, null, t));
        assertNull(refusal("GET", HOST, TAILNET, "same-origin", t));
        assertNull(refusal("POST", HOST, TAILNET, "same-origin", t));
        assertNull(refusal("POST", HOST, null, null, t), "no Origin (a script on the tailnet) as on loopback");
        assertNull(refusal("POST", "Lumen-Thinkpad.tail3e978a.ts.net:8877", "HTTPS://Lumen-Thinkpad.tail3e978a.ts.net:8877",
                "same-origin", t), "scheme and host compare case-insensitively");
        // the API is still reachable on loopback, with loopback's own rule
        assertNull(refusal("POST", "127.0.0.1:18877", "http://127.0.0.1:18877", "same-origin", t));
    }

    @Test
    public void everythingElseIsStillRefused() {
        Set<String> t = trusted(TAILNET, "https://other.example.ts.net");
        // Infra's matrix (BR-73): a foreign Origin on the trusted Host; a foreign Host
        assertNotNull(refusal("POST", HOST, "https://evil.example", null, t));
        assertNotNull(refusal("GET", "evil.example", null, null, t));
        // the Host of entry A with the Origin of entry B
        assertNotNull(refusal("POST", HOST, "https://other.example.ts.net", null, t));
        assertNotNull(refusal("POST", "other.example.ts.net", TAILNET, null, t));
        // plain http on the trusted name: another origin (the "http://" + Host rule is for loopback Hosts only)
        assertNotNull(refusal("POST", HOST, "http://lumen-thinkpad.tail3e978a.ts.net:8877", null, t));
        // a trusted Origin on a loopback Host: loopback's rule applies
        assertNotNull(refusal("POST", "127.0.0.1:18877", TAILNET, "same-origin", t));
        // another port on the same name (the SIM demo :8443, this computer's other services on :443)
        assertNotNull(refusal("GET", "lumen-thinkpad.tail3e978a.ts.net:8443", null, null, t));
        assertNotNull(refusal("POST", HOST, "https://lumen-thinkpad.tail3e978a.ts.net:8443", null, t));
        assertNotNull(refusal("POST", HOST, "https://lumen-thinkpad.tail3e978a.ts.net", null, t));
        assertNotNull(refusal("POST", HOST, "null", null, t));
        assertNotNull(refusal("GET", "lumen-thinkpad.tail3e978a.ts.net.evil.example:8877", null, null, t));
        assertNotNull(refusal("GET", null, null, null, t));
        // a browser saying cross-site is refused even with the trusted Origin
        String r = refusal("POST", HOST, TAILNET, "cross-site", t);
        assertNotNull(r);
        assertTrue(r.contains("Sec-Fetch-Site"), r);
        // the refusal names what is allowed
        r = refusal("GET", "evil.example", null, null, t);
        assertTrue(r.contains(TAILNET), r);
    }

    @Test
    public void defaultPortsAreTheSameOrigin() {
        Set<String> t = trusted("https://name.example.ts.net:443/");
        assertEquals(Collections.singleton("https://name.example.ts.net"), t);
        assertNull(refusal("GET", "name.example.ts.net", null, null, t));
        assertNull(refusal("GET", "name.example.ts.net:443", null, null, t));
        assertNull(refusal("POST", "name.example.ts.net", "https://name.example.ts.net", "same-origin", t));
        assertNull(refusal("POST", "name.example.ts.net:443", "https://name.example.ts.net:443", null, t));
        assertNotNull(refusal("GET", "name.example.ts.net:80", null, null, t));
    }

    @Test
    public void onlyWholeOriginsAreAccepted() {
        assertEquals(TAILNET, ApiSettings.normalizeOrigin(" " + TAILNET.toUpperCase() + "/ "));
        assertNull(ApiSettings.normalizeOrigin("lumen-thinkpad.tail3e978a.ts.net:8877"), "no scheme");
        assertNull(ApiSettings.normalizeOrigin("ftp://name.example"));
        assertNull(ApiSettings.normalizeOrigin("https://name.example/ui"), "a path is not an origin");
        assertNull(ApiSettings.normalizeOrigin("https://name.example?x=1"));
        assertNull(ApiSettings.normalizeOrigin("https://name.example#x"));
        assertNull(ApiSettings.normalizeOrigin("https://user@name.example"));
        assertNull(ApiSettings.normalizeOrigin("https://"));
        assertNull(ApiSettings.normalizeOrigin("https://name.example:0"));
        assertNull(ApiSettings.normalizeOrigin("*"));
        assertNull(ApiSettings.normalizeOrigin(""));
        assertNull(ApiSettings.normalizeOrigin(null));
    }

    @Test
    public void settingsFromTheFileAndTheOverride() throws Exception {
        File f = ApiSettings.fileFor(dir);
        assertTrue(f.getParentFile().mkdirs());
        Files.write(f.toPath(), ("{\"trustedOrigins\": [\"" + TAILNET + "/\", \"not an origin\"]}")
                .getBytes(StandardCharsets.UTF_8));
        ApiSettings s = ApiSettings.load(dir);
        assertEquals(Collections.singleton(TAILNET), s.trustedOriginSet(), "the bad entry is dropped (and logged)");
        // the override (the systemd unit's environment, or this property) replaces the file's list
        System.setProperty("instancelabs.api.trustedOrigins", "https://a.example:8877, https://b.example");
        s = ApiSettings.load(dir);
        assertEquals(new LinkedHashSet<>(Arrays.asList("https://a.example:8877", "https://b.example")),
                s.trustedOriginSet());
        // no file, no override: none
        System.clearProperty("instancelabs.api.trustedOrigins");
        assertTrue(ApiSettings.load(new File(dir, "missing")).trustedOriginSet().isEmpty());
    }
}
