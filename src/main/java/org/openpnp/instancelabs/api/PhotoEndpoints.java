package org.openpnp.instancelabs.api;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.PhotoStore;
import org.openpnp.instancelabs.dispense.TipSetup;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Pictures (PROFILE_CALIBRATION_DESIGN.md §22.4, §22.6): a phone picture copied to this computer and uploaded (one at
 * a time, at most 8 MiB, re-encoded by the engine), the list, each picture and its thumbnail served by id with its
 * SHA-256 as the ETag, removal (the file stays 7 days), restore, the setup's main picture and the links to calibration
 * records. No LAN route: pictures arrive as files (§22.11 item 14).
 */
final class PhotoEndpoints {
    static final String TOO_LARGE = "The picture is larger than 8 MB: send a smaller one (phones can share a reduced "
            + "size).";
    private static final String ID = "([0-9]{1,12})";
    /** Pictures change only by removal: the browser asks again every time and gets a 304 while the file is the same. */
    private static final String CACHE = "private, no-cache";

    private final ApiContext ctx;

    PhotoEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/photos", this::upload, PhotoStore.MAX_REQUEST_BYTES, TOO_LARGE);
        s.route("GET", "/api/photos", this::list);
        s.route("GET", "/api/photos/" + ID + "\\.jpg", r -> file(r, false));
        s.route("GET", "/api/photos/" + ID + "/thumb\\.jpg", r -> file(r, true));
        s.route("DELETE", "/api/photos/" + ID, this::remove);
        s.route("POST", "/api/photos/" + ID + "/restore", this::restore);
        s.route("POST", "/api/photos/" + ID + "/main", this::main);
        s.route("POST", "/api/photos/" + ID + "/links", this::links);
    }

    /**
     * POST /api/photos?kind=tip|setup&setup=KEY&calibrations=41,42&job=&caption=, the body the picture (the browser
     * sends a JPEG it drew itself, so the orientation is right and no metadata leaves it). 201 {photo}.
     */
    Object upload(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc); // no decode while the JVM streams a run or a calibration
        String kind = r.query("kind", null);
        if (!Photo.KIND_TIP.equals(kind) && !Photo.KIND_SETUP.equals(kind)) {
            throw ApiException.badRequest("'kind' must be tip (a picture of the tip for a calibration) or setup (a "
                    + "picture of the tip setup)");
        }
        String key = r.query("setup", null);
        if (key == null) {
            throw ApiException.badRequest("'setup' is required: the tip setup the picture shows");
        }
        TipSetup s = svc.getDb().currentTipSetup(key);
        if (s == null) {
            throw ApiException.notFound("no tip setup '" + key + "'");
        }
        List<Long> calibrations = ids(r.query("calibrations", null), "calibrations");
        byte[] bytes = r.bytes();
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("Send the picture as the request body (a JPEG or PNG).");
        }
        // one upload at a time (§22.4, the engine's pipeline): header, decode, encode and store; never waiting
        if (!PhotoStore.PIPELINE.tryAcquire()) {
            throw new ApiException(409, "busy", "Another picture is being stored: try again in a moment.");
        }
        try {
            DispenseConfig cfg = svc.getConfig();
            PhotoStore.Input in = new PhotoStore.Input();
            in.bytes = bytes;
            in.kind = kind;
            in.source = Photo.SOURCE_UPLOAD;
            in.setupKey = s.key;
            in.needleSinceMs = s.key.equals(cfg.tip.key) ? cfg.needleChangedAtMs : null;
            in.jobId = r.query("job", null);
            in.caption = r.query("caption", null);
            in.calibrationIds.addAll(calibrations);
            in.simulated = svc.isSimulatorConfirmed();
            in.operator = r.query("operator", null);
            Photo p = svc.storePhoto(in);
            return ApiServer.Response.json(201, Views.map("photo", Views.photo(p)));
        }
        finally {
            PhotoStore.PIPELINE.release();
        }
    }

    /** GET /api/photos?setup&calibration&job&kind&removed&limit: newest first. */
    Object list(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        List<Long> cal = ids(r.query("calibration", null), "calibration");
        if (cal.size() > 1) {
            throw ApiException.badRequest("'calibration' is one calibration record id");
        }
        return Views.photos(svc.getDb().photos(r.query("setup", null), cal.isEmpty() ? null : cal.get(0),
                r.query("job", null), r.query("kind", null), "true".equals(r.query("removed", "false")),
                r.queryInt("limit", 100, 1, 1000)));
    }

    /**
     * GET /api/photos/{id}.jpg and /api/photos/{id}/thumb.jpg: by id only, the file resolved under the photos folder;
     * a removed picture is gone at once (410, with when and why), an unchanged one answers 304.
     */
    Object file(ApiServer.Request r, boolean thumb) throws Exception {
        DispenseService svc = ctx.svc();
        long id = Long.parseLong(r.group(1));
        Photo p = svc.getDb().photo(id);
        if (p == null) {
            throw ApiException.notFound("no picture " + id);
        }
        if (p.removedAtMs != null) {
            throw new ApiException(410, "removed", "This picture was removed " + clock(p.removedAtMs)
                    + (p.removedWhy == null ? "" : " (" + p.removedWhy + ")") + ".").with("removedAtMs", p.removedAtMs)
                            .with("why", p.removedWhy);
        }
        PhotoStore store = svc.getPhotoStore();
        File f;
        try {
            f = thumb ? store.thumbFile(p) : store.file(p);
        }
        catch (IllegalStateException e) {
            throw new ApiException(500, "internal", "photo file outside its folder");
        }
        String etag = "\"" + p.sha256 + (thumb ? "-thumb" : "") + "\"";
        if (matches(r.header("If-None-Match"), etag)) {
            return ApiServer.Response.bytes(304, "image/jpeg", new byte[0]).header("ETag", etag).header(
                    "Cache-Control", CACHE);
        }
        if (f == null || !f.isFile()) {
            throw new ApiException(410, "file-missing", "file missing: picture " + id + " has no file any more");
        }
        return ApiServer.Response.bytes(200, "image/jpeg", Files.readAllBytes(f.toPath())).header("ETag", etag)
                .header("Cache-Control", CACHE);
    }

    /** DELETE /api/photos/{id} {why?}: a tip or setup picture; a result photo is the calibration's evidence (409). */
    Object remove(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        long id = require(svc, r.group(1)).id;
        Photo p = svc.getPhotoStore().remove(id, Json.str(b, "why", null), Json.str(b, "operator", null));
        long at = p != null && p.removedAtMs != null ? p.removedAtMs : System.currentTimeMillis();
        return Views.map("removed", true, "id", id, "restoreUntilMs", at + PhotoStore.KEEP_REMOVED_MS, "photo",
                Views.photo(p));
    }

    /** POST /api/photos/{id}/restore: the toast's Undo (410 when the file is gone). */
    Object restore(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        long id = require(svc, r.group(1)).id;
        return Views.photo(svc.getPhotoStore().restore(id, Json.str(b, "operator", null)));
    }

    /** POST /api/photos/{id}/main: "Use as the tip's picture". */
    Object main(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        long id = require(svc, r.group(1)).id;
        return Views.photo(svc.getPhotoStore().setMain(id, Json.str(b, "operator", null)));
    }

    /**
     * POST /api/photos/{id}/links {calibrationIds}: shown with these records (a result photo as their result, any
     * other as a picture of the tip); 409 setup-mismatch for another setup's record, needle-changed for a bottom-camera
     * picture of another needle.
     */
    Object links(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        Photo p = require(svc, r.group(1));
        List<Long> cals = ids(b, "calibrationIds");
        if (cals.isEmpty()) {
            throw ApiException.badRequest("'calibrationIds' is required: the calibration records to show the picture "
                    + "with");
        }
        String role = Photo.KIND_RESULT.equals(p.kind) ? Photo.ROLE_RESULT : Photo.ROLE_TIP;
        List<Photo.Link> links = svc.linkPhotos(Collections.singletonList(p.id), cals, role);
        return Views.map("links", links, "photo", Views.photo(svc.getDb().photo(p.id)));
    }

    // ------------------------------------------------------------------------------------------

    private static Photo require(DispenseService svc, String id) throws Exception {
        Photo p = svc.getDb().photo(Long.parseLong(id));
        if (p == null) {
            throw ApiException.notFound("no picture " + id);
        }
        return p;
    }

    /** Whether If-None-Match names this ETag ("*", a list, weak or strong). */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        for (String t : ifNoneMatch.split(",")) {
            String x = t.trim();
            if (x.startsWith("W/")) {
                x = x.substring(2);
            }
            if (x.equals("*") || x.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    /** Ids given as "41,42" (a query). */
    static List<Long> ids(String s, String name) throws ApiException {
        List<Long> l = new ArrayList<>();
        if (s == null || s.trim().isEmpty()) {
            return l;
        }
        for (String x : s.split(",")) {
            if (!x.trim().isEmpty()) {
                try {
                    l.add(Long.valueOf(x.trim()));
                }
                catch (NumberFormatException e) {
                    throw ApiException.badRequest("'" + name + "' must be record ids separated by commas");
                }
            }
        }
        return l;
    }

    /** Ids given as a JSON list of numbers (a body). */
    static List<Long> ids(JsonObject b, String name) throws ApiException {
        List<Long> l = new ArrayList<>();
        JsonElement e = b.get(name);
        if (e == null || e.isJsonNull()) {
            return l;
        }
        if (!e.isJsonArray()) {
            throw ApiException.badRequest("'" + name + "' must be a list of record ids");
        }
        for (JsonElement x : e.getAsJsonArray()) {
            try {
                double d = x.getAsDouble();
                if (d != Math.rint(d) || d < 1) {
                    throw new NumberFormatException();
                }
                l.add((long) d);
            }
            catch (RuntimeException ex) {
                throw ApiException.badRequest("'" + name + "' must be a list of record ids");
            }
        }
        return l;
    }

    /** "10-29 14:07". */
    static String clock(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(ms));
    }
}
