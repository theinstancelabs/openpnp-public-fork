package org.openpnp.instancelabs.api;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Settings of the local HTTP API (plan §3.3). Read from
 * {@code <configDir>/instancelabs/web-api.json} if present; the API never writes this file, so LAN
 * access can only be turned on by editing it by hand (explicit opt-in).
 *
 * <p>Overrides, highest first: system properties {@code instancelabs.api.port} /
 * {@code instancelabs.api.lan} / {@code instancelabs.api.enabled} / {@code instancelabs.api.trustedOrigins}, then
 * environment variables {@code OPENPNP_IL_API_PORT} / {@code OPENPNP_IL_API_LAN} / {@code OPENPNP_IL_API_ENABLED} /
 * {@code OPENPNP_IL_API_TRUSTED_ORIGINS} (used by tests, the simulator launcher and the deployed service), then the
 * file, then the defaults.</p>
 */
public class ApiSettings {
    public static final String FILE_NAME = "web-api.json";

    /** TCP port. 0 = any free port (tests). */
    public int port = 8765;
    /**
     * Listen on every network interface so other devices on the LAN (e.g. a tablet next to the
     * machine) can open the UI. Off by default: 127.0.0.1 only.
     */
    public boolean lanAccess = false;
    /** Set false to not start the API at all. */
    public boolean enabled = true;
    /** Worker threads for HTTP requests. */
    public int threads = 8;
    /**
     * BR-73: browser origins ({@code https://name:port}) of a TLS proxy in front of this API, such as
     * {@code tailscale serve}, which keeps the browser's Host and Origin: their Host passes the DNS-rebinding guard and
     * their Origin the CSRF guard ({@link ApiServer#browserSafetyRefusal}). Empty by default (127.0.0.1 / localhost
     * only). The server still binds 127.0.0.1 unless {@link #lanAccess}: the proxy connects over loopback. An entry
     * that is not a whole origin (a path, another scheme) is dropped with a warning. The override is a comma-separated
     * list that replaces the file's.
     */
    public List<String> trustedOrigins = new ArrayList<>();

    public static File fileFor(File configDir) {
        return new File(new File(configDir, "instancelabs"), FILE_NAME);
    }

    /** Defaults, then the file (if any), then environment, then system properties. */
    public static ApiSettings load(File configDir) {
        ApiSettings s = new ApiSettings();
        if (configDir != null) {
            File f = fileFor(configDir);
            if (f.isFile()) {
                try {
                    com.google.gson.JsonElement root = new com.google.gson.JsonParser().parse(
                            new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                    // trustedOrigins on its own: a mistyped list must not drop lanAccess, enabled or the port
                    List<String> origins = null;
                    if (root != null && root.isJsonObject() && root.getAsJsonObject().has("trustedOrigins")) {
                        origins = originList(root.getAsJsonObject().remove("trustedOrigins"));
                    }
                    ApiSettings fromFile = Json.GSON.fromJson(root, ApiSettings.class);
                    if (fromFile != null) {
                        s = fromFile;
                    }
                    s.trustedOrigins = origins == null ? new ArrayList<>() : origins;
                }
                catch (IOException | RuntimeException e) {
                    org.pmw.tinylog.Logger.warn(e, "Instance Labs API: ignoring unreadable {}", f);
                }
            }
        }
        s.port = intOverride("instancelabs.api.port", "OPENPNP_IL_API_PORT", s.port);
        s.lanAccess = boolOverride("instancelabs.api.lan", "OPENPNP_IL_API_LAN", s.lanAccess);
        s.enabled = boolOverride("instancelabs.api.enabled", "OPENPNP_IL_API_ENABLED", s.enabled);
        // set, even empty, replaces the file's list (an empty one clears it)
        String origins = System.getProperty("instancelabs.api.trustedOrigins");
        if (origins == null) {
            origins = System.getenv("OPENPNP_IL_API_TRUSTED_ORIGINS");
        }
        if (origins != null) {
            s.trustedOrigins = split(origins);
        }
        if (s.trustedOrigins == null) {
            s.trustedOrigins = new ArrayList<>();
        }
        if (s.threads < 2) {
            s.threads = 2;
        }
        return s;
    }

    private static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        for (String o : list.split(",")) {
            if (!o.trim().isEmpty()) {
                out.add(o.trim());
            }
        }
        return out;
    }

    /** The file's trustedOrigins: a list, or one comma-separated string; null entries and other types dropped. */
    private static List<String> originList(com.google.gson.JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return split(e.getAsString());
        }
        if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) {
                    out.add(x.getAsString());
                }
                else if (x != null && !x.isJsonNull()) {
                    org.pmw.tinylog.Logger.warn("Instance Labs API: ignoring trusted origin {} (not a string)", x);
                }
            }
            return out;
        }
        org.pmw.tinylog.Logger.warn("Instance Labs API: ignoring trustedOrigins {} (a list of origins expected)", e);
        return out;
    }

    /** {@link #trustedOrigins} normalised ({@link #normalizeOrigin}), in order; bad entries dropped with a warning. */
    public Set<String> trustedOriginSet() {
        Set<String> out = new LinkedHashSet<>();
        for (String o : trustedOrigins == null ? Collections.<String> emptyList() : trustedOrigins) {
            String n = normalizeOrigin(o);
            if (n != null) {
                out.add(n);
            }
            else if (o != null && !o.trim().isEmpty()) {
                org.pmw.tinylog.Logger.warn("Instance Labs API: ignoring trusted origin '{}' (not scheme://host[:port] "
                        + "with http or https)", o);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * An origin as browsers send it: lower-case {@code http(s)://host[:port]}, the scheme's default port left out; null
     * for anything else (no scheme, another scheme, a path, query, fragment or user part, no host).
     */
    public static String normalizeOrigin(String origin) {
        if (origin == null) {
            return null;
        }
        String o = origin.trim();
        if (o.endsWith("/")) {
            o = o.substring(0, o.length() - 1);
        }
        try {
            URI u = new URI(o);
            String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) || u.getHost() == null || u.getRawUserInfo() != null
                    || !u.getRawPath().isEmpty() || u.getRawQuery() != null || u.getRawFragment() != null) {
                return null;
            }
            int port = u.getPort();
            if (port == 0 || port > 65535) {
                return null;
            }
            boolean dflt = port < 0 || port == ("https".equals(scheme) ? 443 : 80);
            return scheme + "://" + u.getHost().toLowerCase(Locale.ROOT) + (dflt ? "" : ":" + port);
        }
        catch (Exception e) {
            return null;
        }
    }

    private static String override(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(env);
        }
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static int intOverride(String prop, String env, int def) {
        String v = override(prop, env);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        }
        catch (NumberFormatException e) {
            org.pmw.tinylog.Logger.warn("Instance Labs API: ignoring non-numeric {}={}", prop, v);
            return def;
        }
    }

    private static boolean boolOverride(String prop, String env, boolean def) {
        String v = override(prop, env);
        return v == null ? def : Boolean.parseBoolean(v) || "1".equals(v);
    }
}
