package org.openpnp.instancelabs.api;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.openpnp.instancelabs.board.BoardArtStore;
import org.openpnp.instancelabs.board.EdtRunner;
import org.openpnp.instancelabs.dispense.BoardPadExtractor;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.PadLedger;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.pmw.tinylog.Logger;

import com.google.gson.JsonObject;

/**
 * BR-76: the clean-pad ledger's routes (docs/backend-requests.md BR-76; the web UI's Calibrate › Test board): a board's
 * pads and their states, Board wiped, an agent's whole-plan need and Process's file; status.padLedgers, the previews'
 * {@code padLedger}, and the budget a paste run passes before anything moves. The registered boards' pads are taken
 * here, on OpenPnP's thread (it reads the board files), whenever a registration changed: the engine matches its dots
 * against them on the machine thread. Stage 1: Board wiped records the wipe; the camera's re-check is stage 2.
 */
final class PadLedgerEndpoints {
    private final ApiContext ctx;

    PadLedgerEndpoints(ApiContext ctx) {
        this.ctx = ctx;
    }

    void register(ApiServer s) {
        s.route("GET", "/api/boards/([^/]+)/pads", this::pads);
        s.route("POST", "/api/boards/([^/]+)/wiped", this::wiped);
        s.route("POST", "/api/boards/([^/]+)/need", this::need);
        s.route("POST", "/api/boards/([^/]+)/pads/import", this::importFile);
    }

    // ------------------------------------------------------------------------------------------
    // The registered boards' pads
    // ------------------------------------------------------------------------------------------

    /** The ledger the boards were last given to, and the registrations they were taken from (board key -> id). */
    private PadLedger loadedInto;
    private Map<String, String> loaded = new TreeMap<>();

    /**
     * The ledger's boards: the newest registration of each board file name ({@code file:} keys; a job board has no
     * ledger), its pads (every placement's, disabled ones too) through the registration. Taken again only when a
     * registration changed (or the engine restarted); a board file that cannot be read is left out, logged once.
     */
    synchronized void refresh(DispenseService svc, DispenseConfig c) {
        PadLedger led = svc.getPadLedger();
        Map<String, BoardRegistration> latest = new TreeMap<>();
        for (BoardRegistration r : c.boardRegistrations.values()) {
            String key = r == null ? null : PadLedger.keyOf(r.boardKey);
            if (key != null && r.nominal != null && r.isAffineValid()
                    && (latest.get(key) == null || r.atMs > latest.get(key).atMs)) {
                latest.put(key, r);
            }
        }
        Map<String, String> ids = new TreeMap<>();
        latest.forEach((k, r) -> ids.put(k, String.valueOf(r.id)));
        if (led == loadedInto && ids.equals(loaded)) {
            return;
        }
        List<PadLedger.Board> boards = new ArrayList<>();
        for (Map.Entry<String, BoardRegistration> e : latest.entrySet()) {
            try {
                boards.add(board(svc, e.getKey(), e.getValue()));
            }
            catch (Exception x) {
                Logger.warn("BR-76: the pads of {} ({}) are not known: {}", e.getKey(), e.getValue().boardKey,
                        x.getMessage());
            }
        }
        led.setBoards(boards);
        loadedInto = led;
        loaded = ids;
    }

    private static PadLedger.Board board(DispenseService svc, String key, BoardRegistration r) throws Exception {
        File f = new File(r.boardKey.substring("file:".length()));
        if (!f.isFile()) {
            throw new IllegalStateException("its board file is gone");
        }
        PadTypeResolver resolver = svc.padTypeResolver();
        return EdtRunner.call(() -> {
            // read as OpenPnP does, without putting it in OpenPnP's board list
            Board b = Configuration.createSerializer().read(Board.class, f);
            b.setFile(f);
            BoardLocation bl = new BoardLocation(b);
            bl.setLocation(new Location(LengthUnit.Millimeters, r.nominal.x, r.nominal.y, r.nominal.z,
                    r.nominal.rotation));
            List<PadLedger.Pad> pads = new ArrayList<>();
            for (PadGeometry g : new BoardPadExtractor(resolver, true).extract(r.applyTo(bl)).pads) {
                pads.add(new PadLedger.Pad(g.padId, g.xMm, g.yMm, g.wMm, g.hMm, g.rotationDeg));
            }
            BoardArtStore.ArtRef art;
            try {
                art = BoardArtStore.get().refFor(b);
            }
            catch (RuntimeException e) {
                art = null; // drawn without it
            }
            Location dims = b.getDimensions().convertToUnits(LengthUnit.Millimeters);
            List<Double> m = new ArrayList<>();
            for (double v : r.affine) {
                m.add(v);
            }
            // the shape of a plan's board (BoardPlanView): the UI draws the ledger's pads as it draws a plan's
            return new PadLedger.Board(key, b.getName() == null ? key : b.getName(), r.nominal.z, pads,
                    Views.map("name", b.getName(), "file", f.getAbsolutePath(), "widthMm", dims.getX(), "heightMm",
                            dims.getY(), "side", "Top", "toMachine", m, "art", art == null ? null
                                    : Views.map("id", art.id, "kind", art.kind, "note", art.note)));
        }, DispenseEndpoints.EDT_START_MS);
    }

    /** status.padLedgers. A run's wait recorded under another holder ends with the lock's change of hands. */
    List<Map<String, Object>> status(DispenseService svc, DispenseConfig c) {
        refresh(svc, c);
        MachineLock.Holder h = ctx.lock.current();
        svc.getPadLedger().forgetWaitingExcept(h == null ? null : h.token);
        return svc.getPadLedger().status();
    }

    // ------------------------------------------------------------------------------------------
    // The budget
    // ------------------------------------------------------------------------------------------

    /** Where a plan's dots land (machine mm: the builder aims every dot so). */
    static List<double[]> targets(DispensePlan p) {
        List<double[]> l = new ArrayList<>();
        for (DispensePlan.PlannedDot d : p.dots) {
            l.add(new double[] {d.xMm, d.yMm});
        }
        return l;
    }

    /** The previews' {@code padLedger}: {board, needed, clean}, null when the run pastes no ledger board. */
    Map<String, Object> preview(DispenseService svc, List<double[]> targets) {
        refresh(svc, svc.getConfig());
        PadLedger.Budget b = svc.getPadLedger().budget(targets);
        return b == null ? null : Views.map("board", b.board, "needed", b.needed, "clean", b.clean);
    }

    /**
     * Before anything moves: a run whose dots land at {@code targets} needs more clean pads than its board has (or than
     * the board's plan need) → 409 {@code clean-pads} {board, clean, needed}; an agent's refused call is recorded as
     * waiting (the UI's band; its Board wiped then passes under the agent's lock). {@code force} runs it anyway, on
     * record (events PADS). A run that passes ends the board's plan need and wait.
     */
    void require(DispenseService svc, ApiServer.Request r, List<double[]> targets, boolean force, String what)
            throws ApiException {
        refresh(svc, svc.getConfig());
        PadLedger led = svc.getPadLedger();
        PadLedger.Budget b = led.budget(targets);
        if (b == null) {
            return;
        }
        if (b.isShort()) {
            String why = b.required() > b.needed ? String.format(Locale.ROOT, "Wipe the board first: %d clean, the "
                    + "declared plan needs %d (this run %d).", b.clean, b.required(), b.needed)
                    : String.format(Locale.ROOT, "Wipe the board first: %d clean, this run needs %d.", b.clean, b.needed);
            if (!force) {
                MachineLock.Holder h = ctx.lock.current();
                String token = LockEndpoints.token(r);
                boolean agent = h != null && h.kind == MachineLock.Kind.AGENT && h.token.equals(token);
                if (!LockEndpoints.isUi(r)) {
                    led.waiting(b.board, new PadLedger.Waiting(agent ? h.label : null, what, b.clean, b.required(),
                            System.currentTimeMillis(), agent ? h.token : null, agent ? ctx.lock.calls(h) : 0));
                }
                throw new ApiException(409, "clean-pads", why).with("board", b.board).with("clean", b.clean)
                        .with("needed", b.required());
            }
            svc.getDb().event(null, "WARN", "PADS", "forced past the clean-pad budget (" + what + "): " + why);
        }
        led.started(b.board);
    }

    /** POST /api/run {wiped: true}: the operator wiped the run's own pads ("no paste on its pads now"). */
    void wipedRun(DispenseService svc, ApiServer.Request r, List<double[]> targets) throws ApiException {
        refresh(svc, svc.getConfig());
        for (Map.Entry<String, List<String>> e : svc.getPadLedger().padsAt(targets).entrySet()) {
            svc.getPadLedger().wipe(e.getKey(), e.getValue(), who(r), "I wiped this board (a run's)",
                    System.currentTimeMillis());
        }
    }

    /**
     * POST /api/board/next: a fresh board took the seat of each board taken off ({@code files}, the seats with paste):
     * its ledger starts clean (events PADS); its plan need stays.
     */
    void freshBoards(DispenseService svc, ApiServer.Request r, List<String> files) {
        for (String f : files) {
            String key = PadLedger.keyOf("file:" + f);
            if (key != null && svc.getPadLedger().fresh(key, who(r), System.currentTimeMillis())) {
                svc.getDb().event(null, "INFO", "PADS", "board " + key + ": fresh board: the ledger starts clean");
            }
        }
    }

    /**
     * BR-76 item 8 (b): the UI's Board wiped of a board an agent's refused run waits for passes under the agent's lock,
     * while the agent made no guarded call since and nothing runs (no task, batch, hold or pad calibration).
     */
    boolean wipeWhileWaiting(String board) {
        DispenseService svc = ctx.svcOrNull();
        MachineLock.Holder h = ctx.lock.current();
        if (svc == null || h == null || h.kind != MachineLock.Kind.AGENT || h.revoked) {
            return false;
        }
        PadLedger.Waiting w = svc.getPadLedger().waitingOf(board);
        return w != null && h.token.equals(w.token) && ctx.lock.calls(h) == w.call && !ctx.lock.busy(h)
                && !svc.isBusy() && !ctx.passthrough.running() && !ctx.pressure.isActive()
                && ctx.padCalJobs.lockReason() == null;
    }

    // ------------------------------------------------------------------------------------------
    // Routes
    // ------------------------------------------------------------------------------------------

    /** Who sent it: the web UI's user, the lock holder whose token it carries, else null. */
    private String who(ApiServer.Request r) {
        if (LockEndpoints.isUi(r)) {
            return MachineLock.USER_NAME;
        }
        MachineLock.Holder h = ctx.lock.current();
        String t = LockEndpoints.token(r);
        return h != null && t != null && t.equals(h.token) ? h.label : null;
    }

    Object pads(ApiServer.Request r) throws Exception {
        DispenseService svc = ctx.svc();
        status(svc, svc.getConfig());
        Map<String, Object> out = svc.getPadLedger().pads(r.group(1));
        if (out == null) {
            throw ApiException.notFound("no registered board '" + r.group(1) + "' (a ledger board is a registered "
                    + "board file's name: ftp for ftp.board.xml)");
        }
        return out;
    }

    /**
     * POST /api/boards/{board}/wiped {pads?, note?}: every pad but the fixed ones (or {@code pads}) is clean again.
     * Stage 1 records it as the user says; the camera's re-check (stage 2) answers {@code check} later: null now.
     */
    Object wiped(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        ctx.requireNoPressureHold();
        if (ctx.passthrough.running()) {
            throw ApiException.refused("a G-code batch is running: the board cannot be wiped under it");
        }
        status(svc, svc.getConfig());
        List<String> pads = Json.has(b, "pads") ? Json.strings(b, "pads") : null;
        long now = System.currentTimeMillis();
        Map<String, Object> entry;
        try {
            entry = svc.getPadLedger().wipe(r.group(1), pads, who(r), Json.line(b, "note", null), now);
        }
        catch (IllegalArgumentException e) {
            throw e.getMessage().startsWith("no board") ? ApiException.notFound(e.getMessage())
                    : ApiException.badRequest(e.getMessage());
        }
        String by = who(r);
        svc.getDb().event(null, "INFO", "PADS", "board " + r.group(1) + " wiped" + (by == null ? "" : " by " + by)
                + ": " + (pads == null ? "every pad but the fixed ones" : String.join(", ", pads)));
        Map<String, Object> out = entry == null ? Views.map("board", r.group(1)) : entry;
        out.put("wipedAtMs", now);
        out.put("check", null);
        return out;
    }

    /** POST /api/boards/{board}/need {pads, by?, what?}: an agent's whole-plan need, shown until it clears; 0 clears. */
    Object need(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        Integer pads = Json.integer(b, "pads", null);
        if (pads == null) {
            throw ApiException.badRequest("'pads' (the clean pads the whole plan needs; 0 clears it) is required");
        }
        String by = Json.line(b, "by", null);
        String what = Json.line(b, "what", null);
        if (by != null && by.length() > 40 || what != null && what.length() > 200) {
            throw ApiException.badRequest("'by' is at most 40 characters, 'what' at most 200");
        }
        status(svc, svc.getConfig());
        try {
            svc.getPadLedger().need(r.group(1), pads, by != null ? by : who(r), what, System.currentTimeMillis());
        }
        catch (IllegalArgumentException e) {
            throw e.getMessage().startsWith("no board") ? ApiException.notFound(e.getMessage())
                    : ApiException.badRequest(e.getMessage());
        }
        Map<String, Object> out = svc.getPadLedger().pads(r.group(1));
        if (out != null) {
            out.remove("pads");
            out.remove("offPad");
            out.remove("view");
        }
        return out == null ? Views.map("board", r.group(1), "need", pads == 0 ? null : pads) : out;
    }

    /**
     * POST /api/boards/{board}/pads/import: Process's interim ledger file (runs/ledger_ftp.json) as the board's: its
     * fixed pads, wipes and pads pasted since its last wipe; 409 once the engine recorded a wipe of its own.
     */
    Object importFile(ApiServer.Request r) throws Exception {
        JsonObject b = r.json();
        DispenseService svc = ctx.svc();
        ctx.requireEngineIdle(svc);
        status(svc, svc.getConfig());
        try {
            svc.getPadLedger().importProcess(r.group(1), b, System.currentTimeMillis());
        }
        catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        catch (IllegalStateException e) {
            throw ApiException.refused(e.getMessage());
        }
        svc.getDb().event(null, "INFO", "PADS", "board " + r.group(1) + ": Process's ledger imported");
        Map<String, Object> out = svc.getPadLedger().pads(r.group(1));
        return out == null ? Views.map("board", r.group(1), "imported", true, "note", "kept until a board file named "
                + r.group(1) + ".board.xml is registered") : out;
    }
}
