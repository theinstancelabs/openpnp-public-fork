package org.openpnp.instancelabs.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.ConfigurationListener;
import org.openpnp.instancelabs.dispense.EngineRefusal;
import org.openpnp.model.Configuration;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Local HTTP API and web UI of the Instance Labs fork (plan §3.3): JDK {@code HttpServer}, JSON
 * via Gson, static UI files from the jar ({@value #RESOURCE_ROOT}). Bound to 127.0.0.1 unless
 * {@link ApiSettings#lanAccess} is set by hand. Started when OpenPnP's configuration has loaded
 * ({@link #install(Configuration)} is called from {@code org.openpnp.Main}).
 *
 * <p>Browser-safety: no CORS headers are ever sent; the Host header must name this machine
 * (DNS-rebinding guard) unless LAN access is on; state-changing requests carrying a foreign
 * {@code Origin} or {@code Sec-Fetch-Site: cross-site} are refused (CSRF guard). Plain
 * {@code curl -X POST http://127.0.0.1:8765/api/stop} works. BR-73: a trusted origin
 * ({@link ApiSettings#trustedOrigins}, the machine's tailnet name behind {@code tailscale serve}) passes both guards
 * with its own Host and Origin ({@link #browserSafetyRefusal}).</p>
 */
public final class ApiServer {
    public static final String RESOURCE_ROOT = "/org/openpnp/instancelabs/web/";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern STATIC_NAME = Pattern.compile("[A-Za-z0-9_-]+\\.(html|js|css|svg|png)");

    private static volatile ApiServer instance;

    private final ApiSettings settings;
    /** BR-73: {@link ApiSettings#trustedOriginSet()}, read once at start. */
    private final Set<String> trustedOrigins;
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Route> routes = new ArrayList<>();
    private final ApiContext context;
    private final MachineEndpoints machineEndpoints;
    /** BR-72: the machine lock's routes and the guard on every write. */
    private final LockEndpoints lockEndpoints;

    // ------------------------------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------------------------------

    /** Starts the API once the configuration is complete. Never throws. */
    public static void install(Configuration configuration) {
        configuration.addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration c) {
                try {
                    ApiSettings s = ApiSettings.load(c.getConfigurationDirectory());
                    if (!s.enabled) {
                        Logger.info("Instance Labs API disabled by settings");
                        return;
                    }
                    ApiServer server = start(c, s);
                    Logger.info("Instance Labs web UI: {}", server.getUrl());
                }
                catch (Throwable t) {
                    Logger.error(t, "Instance Labs API failed to start");
                }
            }
        });
    }

    /** (Re)starts the server for a loaded configuration. */
    public static synchronized ApiServer start(Configuration configuration, ApiSettings settings)
            throws IOException {
        if (instance != null) {
            instance.stop();
        }
        instance = new ApiServer(configuration, settings);
        return instance;
    }

    /** The running server, or null. */
    public static ApiServer get() {
        return instance;
    }

    private ApiServer(Configuration configuration, ApiSettings settings) throws IOException {
        this.settings = settings;
        this.trustedOrigins = settings.trustedOriginSet();
        if (!trustedOrigins.isEmpty()) {
            Logger.info("Instance Labs API: trusted origins {}", trustedOrigins);
        }
        InetSocketAddress addr = settings.lanAccess ? new InetSocketAddress(settings.port)
                : new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.port);
        this.server = HttpServer.create(addr, 0);
        AtomicInteger n = new AtomicInteger();
        // Unbounded (cached) so STOP always gets a thread even while other requests block
        // (camera capture, ?wait=true runs). Bounded by the few clients: localhost, and since BR-73 the user's tailnet
        // devices through tailscale serve (the tailnet's access rules are the only access control: no login).
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "il-api-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.context = new ApiContext(configuration);
        this.machineEndpoints = new MachineEndpoints(context);
        machineEndpoints.register(this);
        this.lockEndpoints = new LockEndpoints(context, machineEndpoints);
        lockEndpoints.register(this);
        new ControlEndpoints(context, machineEndpoints).register(this);
        DispenseEndpoints dispense = new DispenseEndpoints(context, machineEndpoints);
        dispense.register(this);
        dispense.registration.register(this);
        new CalibrationEndpoints(context, dispense).register(this);
        new PadTypeEndpoints(context).register(this);
        PadProfileEndpoints padProfile = new PadProfileEndpoints(context, dispense);
        padProfile.register(this);
        new PadCalibrationEndpoints(context, dispense, padProfile).register(this);
        new TipSetupEndpoints(context, dispense).register(this);
        new PhotoEndpoints(context).register(this);
        new SimEndpoints(context).register(this);
        new BoardImportEndpoints(context, dispense).register(this);
        new HeightSwitchEndpoints(context).register(this);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public int getPort() {
        return server.getAddress().getPort();
    }

    public String getUrl() {
        return "http://127.0.0.1:" + getPort() + "/";
    }

    public ApiSettings getSettings() {
        return settings;
    }

    /** BR-73: the trusted origins in force (normalised). */
    public Set<String> getTrustedOrigins() {
        return trustedOrigins;
    }

    ApiContext getContext() {
        return context;
    }

    public synchronized void stop() {
        server.stop(0);
        executor.shutdownNow();
        lockEndpoints.shutdown();
        context.shutdown();
        machineEndpoints.shutdown();
        if (instance == this) {
            instance = null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------------------------------

    @FunctionalInterface
    public interface Handler {
        /** Returns a {@link Response}, or any object to send as JSON with status 200. */
        Object handle(Request request) throws Exception;
    }

    private static final class Route {
        final String method;
        final Pattern pattern;
        final Handler handler;
        final int maxBodyBytes;
        /** The 413 reason, or null for the generic one. */
        final String tooLarge;

        Route(String method, String regex, Handler handler, int maxBodyBytes, String tooLarge) {
            this.method = method;
            this.pattern = Pattern.compile(regex);
            this.handler = handler;
            this.maxBodyBytes = maxBodyBytes;
            this.tooLarge = tooLarge;
        }
    }

    /** Registers a route; {@code regex} must match the whole path, groups are path parameters. */
    void route(String method, String regex, Handler handler) {
        route(method, regex, handler, MAX_BODY);
    }

    /** As above with its own request body limit (uploads); every other route keeps {@value #MAX_BODY} bytes. */
    void route(String method, String regex, Handler handler, int maxBodyBytes) {
        route(method, regex, handler, maxBodyBytes, null);
    }

    /** As above with the 413 reason the operator reads. */
    void route(String method, String regex, Handler handler, int maxBodyBytes, String tooLargeReason) {
        routes.add(new Route(method, regex, handler, maxBodyBytes, tooLargeReason));
    }

    /** The route a request will be dispatched to (path and method match), or null. */
    private Route routeFor(String method, String path) {
        for (Route r : routes) {
            if (r.method.equals(method) && r.pattern.matcher(path).matches()) {
                return r;
            }
        }
        return null;
    }

    public static final class Request {
        public final String method;
        public final String path;
        public final Map<String, String> query;
        private final HttpExchange exchange;
        private final byte[] raw;
        private String body;
        private String[] groups = new String[0];
        /** BR-72: the lock holder's call this request is (null: not a guarded write, or nobody holds the lock). */
        MachineLock.Activity lockActivity;

        Request(HttpExchange ex, byte[] raw) {
            this.exchange = ex;
            this.method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
            URI uri = ex.getRequestURI();
            this.path = uri.getRawPath();
            this.query = parseQuery(uri.getRawQuery());
            this.raw = raw;
        }

        public String group(int i) {
            return groups[i - 1];
        }

        /** The request body as sent (an upload). */
        public byte[] bytes() {
            return raw;
        }

        public JsonObject json() throws ApiException {
            if (body == null) {
                body = new String(raw, StandardCharsets.UTF_8);
            }
            return Json.parseObject(body);
        }

        public String query(String name, String def) {
            String v = query.get(name);
            return v == null || v.isEmpty() ? def : v;
        }

        public int queryInt(String name, int def, int min, int max) throws ApiException {
            String v = query.get(name);
            if (v == null || v.isEmpty()) {
                return def;
            }
            try {
                return Math.max(min, Math.min(max, Integer.parseInt(v)));
            }
            catch (NumberFormatException e) {
                throw ApiException.badRequest("query parameter '" + name + "' must be a whole number");
            }
        }

        public String header(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        private static Map<String, String> parseQuery(String raw) {
            Map<String, String> m = new LinkedHashMap<>();
            if (raw == null || raw.isEmpty()) {
                return m;
            }
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                String k = decode(i < 0 ? kv : kv.substring(0, i));
                String v = i < 0 ? "" : decode(kv.substring(i + 1));
                m.put(k, v);
            }
            return m;
        }

        static String decode(String s) {
            try {
                return URLDecoder.decode(s, "UTF-8");
            }
            catch (Exception e) {
                return s;
            }
        }
    }

    public static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        /** Set after the defaults, so a handler can override one (Cache-Control). */
        final Map<String, String> headers = new LinkedHashMap<>();

        private Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        public Response header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public static Response json(int status, Object o) {
            return new Response(status, "application/json; charset=utf-8",
                    Json.toJson(o).getBytes(StandardCharsets.UTF_8));
        }

        public static Response bytes(int status, String contentType, byte[] body) {
            return new Response(status, contentType, body);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Dispatch
    // ------------------------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        Response resp;
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        String path = ex.getRequestURI().getRawPath();
        try {
            checkBrowserSafety(ex, method);
            Route route = routeFor(method, path);
            byte[] body = readBody(ex, route == null ? MAX_BODY : route.maxBodyBytes,
                    route == null ? null : route.tooLarge);
            Request req = new Request(ex, body);
            resp = dispatch(req);
        }
        catch (Throwable t) {
            resp = error(t, method, path);
        }
        send(ex, resp);
        if (!"GET".equals(method)) {
            Logger.info("Instance Labs API {} {} -> {}", method, path, resp.status);
        }
    }

    private Response dispatch(Request req) throws Exception {
        if ("GET".equals(req.method) || "HEAD".equals(req.method)) {
            Response r = staticFile(req.path);
            if (r != null) {
                return r;
            }
        }
        boolean pathMatched = false;
        for (Route route : routes) {
            Matcher m = route.pattern.matcher(req.path);
            if (!m.matches()) {
                continue;
            }
            pathMatched = true;
            if (!route.method.equals(req.method)) {
                continue;
            }
            String[] g = new String[m.groupCount()];
            for (int i = 0; i < g.length; i++) {
                g[i] = Request.decode(m.group(i + 1));
            }
            req.groups = g;
            // BR-72: a write passes the machine lock first (409 locked / lock-lost / no-lock)
            req.lockActivity = lockEndpoints.admit(req);
            Object out;
            try {
                out = route.handler.handle(req);
            }
            finally {
                context.lock.end(req.lockActivity);
            }
            return out instanceof Response ? (Response) out : Response.json(200, out);
        }
        if (pathMatched) {
            throw new ApiException(405, "method-not-allowed", req.method + " is not supported on " + req.path);
        }
        throw ApiException.notFound("no such endpoint: " + req.path);
    }

    private Response error(Throwable t, String method, String path) {
        while (t instanceof ExecutionException && t.getCause() != null) {
            t = t.getCause();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        int status;
        if (t instanceof ApiException) {
            ApiException a = (ApiException) t;
            status = a.status;
            body.put("error", a.code);
            body.put("reason", a.getMessage());
            body.putAll(a.details);
        }
        else if (t instanceof EngineRefusal) {
            // the engine's own codes (tip setups design §22): tip-changed, needle-changed, setup-mismatch, ...
            EngineRefusal a = (EngineRefusal) t;
            status = a.status;
            body.put("error", a.code);
            body.put("reason", a.getMessage());
            body.putAll(a.details);
        }
        else if (t instanceof IllegalArgumentException) {
            status = 400;
            body.put("error", "bad-request");
            body.put("reason", t.getMessage());
        }
        else if (t instanceof IllegalStateException) {
            // The engine reports "busy", "unavailable", "location not set" this way.
            status = 409;
            body.put("error", "refused");
            body.put("reason", t.getMessage());
        }
        else {
            status = 500;
            body.put("error", "internal");
            body.put("reason", t.getClass().getSimpleName() + ": " + t.getMessage());
            Logger.error(t, "Instance Labs API {} {} failed", method, path);
        }
        return Response.json(status, body);
    }

    /** DNS-rebinding and CSRF guards (see class comment). */
    private void checkBrowserSafety(HttpExchange ex, String method) throws ApiException {
        String refusal = browserSafetyRefusal(method, ex.getRequestHeaders().getFirst("Host"),
                ex.getRequestHeaders().getFirst("Origin"), ex.getRequestHeaders().getFirst("Sec-Fetch-Site"),
                settings.lanAccess, trustedOrigins);
        if (refusal != null) {
            throw new ApiException(403, "forbidden", refusal);
        }
    }

    /**
     * Why a request fails the DNS-rebinding or CSRF guard, or null when it passes. Host: loopback, a trusted origin's
     * {@code host[:port]} (BR-73), or anything with LAN access. Origin (writes only; none at all passes, as curl): a
     * trusted Host needs exactly its own trusted origin, any other Host {@code "http://" + Host} (same-origin over
     * plain http). Then {@code Sec-Fetch-Site} must be same-origin or none.
     */
    static String browserSafetyRefusal(String method, String host, String origin, String fetchSite, boolean lanAccess,
            Set<String> trustedOrigins) {
        // loopback keeps its own rule even with a trusted entry naming it (the local UI's Stop must never be refused)
        String trusted = host == null || isLoopbackHost(host) ? null : trustedOriginOf(host, trustedOrigins);
        if (!lanAccess && trusted == null && (host == null || !isLoopbackHost(host))) {
            // never the trusted names: a DNS-rebinding page reads this answer as same-origin (#18 review)
            return "Host '" + host + "' is not allowed: this API answers 127.0.0.1 / localhost and its trusted origins "
                    + "only";
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return null;
        }
        if (origin != null && !(trusted != null ? trusted.equals(ApiSettings.normalizeOrigin(origin))
                : origin.equalsIgnoreCase("http://" + host))) {
            return "cross-site request refused (Origin " + origin + " is not this server)";
        }
        if (fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none")) {
            return "cross-site request refused (Sec-Fetch-Site: " + fetchSite + ")";
        }
        return null;
    }

    /**
     * The trusted origin whose {@code host[:port]} this Host header names (case-insensitive; the scheme's default port
     * may be written out or left out), or null.
     */
    static String trustedOriginOf(String hostHeader, Set<String> trustedOrigins) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        for (String o : trustedOrigins) {
            int sep = o.indexOf("://");
            String authority = o.substring(sep + 3);
            String withDefaultPort = authority + (o.startsWith("https") ? ":443" : ":80");
            if (h.equals(authority) || (authority.lastIndexOf(':') <= authority.lastIndexOf(']')
                    && h.equals(withDefaultPort))) {
                return o;
            }
        }
        return null;
    }

    static boolean isLoopbackHost(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && h.substring(1, end).equals("::1");
        }
        int colon = h.indexOf(':');
        if (colon >= 0) {
            h = h.substring(0, colon);
        }
        return h.equals("localhost") || h.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    /** The body, at most {@code max} bytes: a larger Content-Length is refused before anything is read. */
    private static byte[] readBody(HttpExchange ex, int max, String tooLarge) throws IOException, ApiException {
        ApiException large = new ApiException(413, "too-large",
                tooLarge != null ? tooLarge : "request body larger than " + max + " bytes");
        String declared = ex.getRequestHeaders().getFirst("Content-Length");
        if (declared != null) {
            try {
                if (Long.parseLong(declared.trim()) > max) {
                    throw large;
                }
            }
            catch (NumberFormatException e) {
                throw ApiException.badRequest("Content-Length is not a number");
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw large;
                }
            }
            return out.toByteArray();
        }
    }

    private Response staticFile(String path) throws IOException {
        String name;
        if (path.equals("/") || path.equals("/index.html")) {
            name = "index.html";
        }
        else if (path.startsWith("/static/")) {
            name = path.substring("/static/".length());
            if (!STATIC_NAME.matcher(name).matches()) {
                return null;
            }
        }
        else {
            return null;
        }
        try (InputStream in = ApiServer.class.getResourceAsStream(RESOURCE_ROOT + name)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return Response.bytes(200, contentType(name), out.toByteArray());
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (n.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (n.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (n.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, Response r) throws IOException {
        try {
            ex.getResponseHeaders().set("Content-Type", r.contentType);
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            if (r.contentType.startsWith("text/html")) {
                ex.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; "
                                + "base-uri 'none'; form-action 'none'");
            }
            for (Map.Entry<String, String> h : r.headers.entrySet()) {
                ex.getResponseHeaders().set(h.getKey(), h.getValue());
            }
            // a 304 (an unchanged picture) has no body
            boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod()) || r.status == 304;
            ex.sendResponseHeaders(r.status, head ? -1 : r.body.length);
            if (!head) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(r.body);
                }
            }
        }
        finally {
            ex.close();
        }
    }

    /** All registered routes as "METHOD regex" (for the index endpoint and docs). */
    List<String> describeRoutes() {
        List<String> l = new ArrayList<>();
        for (Route r : routes) {
            l.add(r.method + " " + r.pattern.pattern());
        }
        return Collections.unmodifiableList(l);
    }
}
