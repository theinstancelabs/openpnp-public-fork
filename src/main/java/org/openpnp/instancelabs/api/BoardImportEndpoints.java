package org.openpnp.instancelabs.api;

import java.util.Map;

import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.instancelabs.board.BoardImporter;
import org.openpnp.instancelabs.dispense.DispenseService;

/**
 * KiCad board upload (BOARD_IMPORT_DESIGN.md §3.2): preview, save and attach take the raw file (up to 12 MB) and are
 * refused while a dispense task runs (and no task starts while they read it); the board drawings are served by id with
 * an immutable cache header (§4.4).
 */
final class BoardImportEndpoints {
    static final String ART_CACHE = "private, max-age=31536000, immutable";

    private final ApiContext ctx;
    private final DispenseEndpoints dispense;

    BoardImportEndpoints(ApiContext ctx, DispenseEndpoints dispense) {
        this.ctx = ctx;
        this.dispense = dispense;
    }

    void register(ApiServer s) {
        s.route("POST", "/api/boards/import", this::preview, BoardImporter.MAX_UPLOAD, BoardImporter.TOO_LARGE);
        s.route("POST", "/api/boards/import/save", this::save, BoardImporter.MAX_UPLOAD, BoardImporter.TOO_LARGE);
        s.route("POST", "/api/boards/import/attach", this::attach, BoardImporter.MAX_UPLOAD,
                BoardImporter.TOO_LARGE);
        s.route("GET", "/api/board-art/([^/]*)", this::art);
    }

    /** ?file=<browser file name>[&pasteDnp=1]: what a save would make of the file. */
    Object preview(ApiServer.Request r) throws Exception {
        BoardImporter imp = importer();
        try {
            return imp.preview(r.bytes(), r.query("file", ""), flag(r, "pasteDnp"));
        }
        catch (BoardImporter.ImportException e) {
            throw api(e);
        }
    }

    /** ?file=…&name=…&replace=0|1&sha256=…&decisions=…[&pasteDnp=1]: 201 with the saved board and the board list. */
    Object save(ApiServer.Request r) throws Exception {
        BoardImporter imp = importer();
        Map<String, Object> out;
        try {
            out = imp.save(r.bytes(), r.query("file", ""), r.query("name", null), flag(r, "replace"),
                    r.query("sha256", null), r.query("decisions", null), flag(r, "pasteDnp"));
        }
        catch (BoardImporter.ImportException e) {
            throw api(e);
        }
        out.put("boards", dispense.boards());
        return ApiServer.Response.json(201, out);
    }

    /** ?file=…&board=<listed board file>&name=…&replace=0|1&sha256=…: 201, the drawing now belongs to that board. */
    Object attach(ApiServer.Request r) throws Exception {
        BoardImporter imp = importer();
        Map<String, Object> out;
        try {
            out = imp.attach(r.bytes(), r.query("file", ""), r.query("board", null), r.query("name", null),
                    flag(r, "replace"), r.query("sha256", null), flag(r, "pasteDnp"));
        }
        catch (BoardImporter.ImportException e) {
            throw api(e);
        }
        out.put("boards", dispense.boards());
        return ApiServer.Response.json(201, out);
    }

    /** The drawing bytes of an art id (content addressed, so cached for good); 404 art-gone when unknown. */
    Object art(ApiServer.Request r) throws Exception {
        byte[] b = BoardArtStore.get().bytes(r.group(1));
        if (b == null) {
            throw new ApiException(404, "art-gone", "This board drawing is no longer known: plan the board again");
        }
        return ApiServer.Response.bytes(200, "application/json; charset=utf-8", b).header("Cache-Control", ART_CACHE);
    }

    /**
     * The importer: once it holds the pipeline, the engine must be idle (no file is read while a dispense,
     * registration or probe task runs, or a pad calibration is between its steps), and while it holds it no such
     * task starts ({@link ApiContext#requireEngineIdle}).
     */
    private BoardImporter importer() throws ApiException {
        DispenseService svc = ctx.availableSvc();
        return new BoardImporter(ctx.configuration, svc, () -> {
            if (svc.isBusy() || ctx.padCalJobs.lockReason() != null) {
                throw ApiException.refused("Upload the board when the run has finished.");
            }
        });
    }

    private static boolean flag(ApiServer.Request r, String name) throws ApiException {
        String v = r.query(name, "0");
        if (v.equals("1") || v.equalsIgnoreCase("true")) {
            return true;
        }
        if (v.equals("0") || v.equalsIgnoreCase("false")) {
            return false;
        }
        throw ApiException.badRequest("'" + name + "' must be 0 or 1");
    }

    private static ApiException api(BoardImporter.ImportException e) {
        ApiException a = new ApiException(e.status, e.code, e.getMessage());
        a.details.putAll(e.details);
        return a;
    }
}
