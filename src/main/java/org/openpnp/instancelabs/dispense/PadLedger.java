package org.openpnp.instancelabs.dispense;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * BR-76: which pads of each registered board carry paste since the board's last wipe (docs/backend-requests.md BR-76,
 * with the fields of Process's interim tools/padledger.py). A dot is matched by X/Y when it is laid ({@link DotWatch})
 * to every pad of the registered boards, whatever the run calls it: on a pad it adds a layer, on the board but on no
 * pad it is listed off-pad, and a turn more than {@link #AIR_MM} above the board's top marks nothing (air turns, the
 * purge). A wipe makes every pad but the fixed ones (old solder) clean again. Kept per board key (the registered board
 * file's name: {@code ftp}) in {@code <config>/instancelabs/pad-ledgers.json}, written at every change, so the counts
 * survive a restart; the boards' pads ({@link #setBoards}) come from their registrations and live in memory.
 */
public class PadLedger {
    public static final String FILE_NAME = "pad-ledgers.json";
    /** A turn with the tip more than this above the board's top marks nothing (Process: "~1 mm"). */
    public static final double AIR_MM = 1.0;
    /** How far outside a pad's opening a dot still counts on it: the landing's scatter. */
    static final double SLACK_MM = 0.15;
    /** Around the board's outline and its pads a dot is still the board's (off-pad). */
    static final double EDGE_MM = 3;
    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting()
            .create();

    /** One pad of a registered board: its opening, w x h mm turned by rotation degrees, at x, y (machine mm). */
    public static final class Pad {
        public final String id, set;
        public final double x, y, w, h, rotation;

        public Pad(String id, double x, double y, double w, double h, double rotation) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rotation = rotation;
            // opaque, as Process's D and R: the part's letters ("LED" of LED1.2), else what comes before the first dot
            String s = id.replaceAll("[^A-Za-z].*", "");
            this.set = s.isEmpty() ? id.replaceAll("\\..*", "") : s;
        }

        /** The point lies in the opening grown by {@link #SLACK_MM}. */
        boolean holds(double px, double py) {
            double a = Math.toRadians(rotation), dx = px - x, dy = py - y;
            double u = dx * Math.cos(a) + dy * Math.sin(a), v = -dx * Math.sin(a) + dy * Math.cos(a);
            return Math.abs(u) <= w / 2 + SLACK_MM && Math.abs(v) <= h / 2 + SLACK_MM;
        }
    }

    /**
     * A registered board: its pads, the Z of its top (the N1 frame: its registration's seat) and its drawing
     * ({@code {art, widthMm, heightMm, toMachine}}, the shape of a plan's {@code board}).
     */
    public static final class Board {
        public final String key, name;
        public final double topZMm;
        public final List<Pad> pads;
        public final Map<String, Object> view;
        /** x0, y0, x1, y1: the outline's and the pads' box, {@link #EDGE_MM} wider. */
        private final double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

        public Board(String key, String name, double topZMm, List<Pad> pads, Map<String, Object> view) {
            this.key = key;
            this.name = name;
            this.topZMm = topZMm;
            this.pads = pads;
            this.view = view;
            for (Pad p : pads) {
                double r = Math.max(p.w, p.h) / 2;
                grow(p.x - r, p.y - r);
                grow(p.x + r, p.y + r);
            }
            double[] m = matrix(view == null ? null : view.get("toMachine"));
            double w = num(view == null ? null : view.get("widthMm")), h = num(view == null ? null : view.get("heightMm"));
            if (m != null && w > 0 && h > 0) {
                for (double[] c : new double[][] {{0, 0}, {w, 0}, {0, h}, {w, h}}) {
                    grow(m[0] * c[0] + m[2] * c[1] + m[4], m[1] * c[0] + m[3] * c[1] + m[5]);
                }
            }
        }

        private void grow(double x, double y) {
            box[0] = Math.min(box[0], x - EDGE_MM);
            box[1] = Math.min(box[1], y - EDGE_MM);
            box[2] = Math.max(box[2], x + EDGE_MM);
            box[3] = Math.max(box[3], y + EDGE_MM);
        }

        boolean holds(double x, double y) {
            return x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];
        }

        /** The pad whose opening holds the point, the nearest centre if several do, else null. */
        Pad padAt(double x, double y) {
            Pad best = null;
            for (Pad p : pads) {
                if (p.holds(x, y) && (best == null || Math.hypot(p.x - x, p.y - y) < Math.hypot(best.x - x, best.y - y))) {
                    best = p;
                }
            }
            return best;
        }

        private static double[] matrix(Object o) {
            if (!(o instanceof List) || ((List<?>) o).size() != 6) {
                return null;
            }
            double[] m = new double[6];
            for (int i = 0; i < 6; i++) {
                m[i] = num(((List<?>) o).get(i));
            }
            return m;
        }

        private static double num(Object o) {
            return o instanceof Number ? ((Number) o).doubleValue() : 0;
        }
    }

    /** What a run would paste on a ledger board ({@link #budget}). */
    public static final class Budget {
        public final String board, name;
        /** The distinct pads the run pastes, and the board's clean pads now. */
        public final int needed, clean;
        /** The board's whole-plan need ({@link #need}), or null. */
        public final Integer planNeed;

        Budget(String board, String name, int needed, int clean, Integer planNeed) {
            this.board = board;
            this.name = name;
            this.needed = needed;
            this.clean = clean;
            this.planNeed = planNeed;
        }

        /** The clean pads it takes: the run's need, or the plan's while one is set and larger. */
        public int required() {
            return Math.max(needed, planNeed == null ? 0 : planNeed);
        }

        public boolean isShort() {
            return required() > clean;
        }
    }

    /** A run refused for clean pads that waits for the wipe (the UI's band, BR-76 item 8): memory only. */
    public static final class Waiting {
        public final String by, what;
        public final int clean, needed;
        public final long atMs;
        /** The lock holder's token and its guarded call that was refused (null: no agent held the lock). */
        public final String token;
        public final int call;

        public Waiting(String by, String what, int clean, int needed, long atMs, String token, int call) {
            this.by = by;
            this.what = what;
            this.clean = clean;
            this.needed = needed;
            this.atMs = atMs;
            this.token = token;
            this.call = call;
        }
    }

    // ------------------------------------------------------------------------------------------
    // The file (Gson-friendly)
    // ------------------------------------------------------------------------------------------

    static final class Mark {
        long atMs;
        String by, kind, run;
        int layers;
    }

    static final class Wipe {
        long atMs;
        String by, note;
        /** The pads wiped; null: every pad. */
        List<String> pads;
        /** From Process's file ({@link #importProcess}). */
        boolean imported;
    }

    static final class Off {
        double x, y;
        long atMs;
        String by, kind;
    }

    static final class Need {
        int pads;
        String by, what;
        long atMs;
    }

    static final class Ledger {
        List<Wipe> wipes = new ArrayList<>();
        Map<String, String> fixed = new TreeMap<>();
        Map<String, Mark> pasted = new TreeMap<>();
        List<Off> offPad = new ArrayList<>();
        Need need;
        Long importedAtMs;
    }

    static final class Data {
        int version = 1;
        Map<String, Ledger> boards = new TreeMap<>();
    }

    private final File file;
    private final Data data;
    private final Map<String, Board> boards = new TreeMap<>();
    private final Map<String, Waiting> waiting = new HashMap<>();

    /** The ledgers of {@code file}; a file that cannot be read is kept aside and the ledgers start empty. */
    public PadLedger(File file) {
        this.file = file;
        this.data = read(file);
    }

    /** {@code <configDir>/instancelabs/pad-ledgers.json}. */
    public static PadLedger open(File configDir) {
        return new PadLedger(new File(new File(configDir, DispenseConfig.SUBDIR), FILE_NAME));
    }

    private static Data read(File f) {
        if (!f.isFile()) {
            return new Data();
        }
        try {
            Data d = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), Data.class);
            d = d == null ? new Data() : d;
            d.boards = d.boards == null ? new TreeMap<>() : d.boards;
            for (Ledger l : d.boards.values()) {
                l.wipes = l.wipes == null ? new ArrayList<>() : l.wipes;
                l.fixed = l.fixed == null ? new TreeMap<>() : l.fixed;
                l.pasted = l.pasted == null ? new TreeMap<>() : l.pasted;
                l.offPad = l.offPad == null ? new ArrayList<>() : l.offPad;
            }
            return d;
        }
        catch (IOException | JsonParseException | IllegalStateException e) {
            File aside = new File(f.getPath() + ".unreadable-" + System.currentTimeMillis());
            Logger.error("{} cannot be read ({}): kept as {}, the pad ledgers start empty", f, e.getMessage(), aside);
            if (!f.renameTo(aside)) {
                Logger.error("{} could not be moved aside", f);
            }
            return new Data();
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParentFile().toPath());
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (IOException e) {
            throw new UncheckedIOException("the pad ledgers could not be written to " + file, e);
        }
    }

    /** The ledger key of a registration's board key: the board file's name less {@code .board.xml}; null for a job board. */
    public static String keyOf(String boardKey) {
        if (boardKey == null || !boardKey.startsWith("file:")) {
            return null;
        }
        String n = new File(boardKey.substring("file:".length())).getName();
        return n.endsWith(".board.xml") ? n.substring(0, n.length() - ".board.xml".length()) : n;
    }

    // ------------------------------------------------------------------------------------------
    // The boards and their dots
    // ------------------------------------------------------------------------------------------

    /** The registered boards (their pads in machine mm), replacing the ones before. */
    public synchronized void setBoards(Collection<Board> list) {
        boards.clear();
        for (Board b : list) {
            boards.put(b.key, b);
        }
    }

    private Ledger ledger(String board) {
        return data.boards.computeIfAbsent(board, k -> new Ledger());
    }

    /**
     * One dot, or a line of paste: the points {x, y, z} where it lands (machine mm; z the tip's height in the N1 frame).
     * Each pad of a registered board under a point that is at most {@link #AIR_MM} above the board's top gains a layer;
     * a dot on a board but on none of its pads is listed off-pad there.
     */
    public synchronized void dot(List<double[]> points, long atMs, String by, String kind, String run) {
        boolean changed = false;
        for (Board b : boards.values()) {
            Set<Pad> hit = new LinkedHashSet<>();
            double[] off = null;
            for (double[] p : points) {
                if (!b.holds(p[0], p[1]) || p[2] - b.topZMm > AIR_MM) {
                    continue;
                }
                Pad pad = b.padAt(p[0], p[1]);
                if (pad != null) {
                    hit.add(pad);
                }
                else if (off == null) {
                    off = p;
                }
            }
            if (hit.isEmpty() && off == null) {
                continue;
            }
            Ledger l = ledger(b.key);
            for (Pad pad : hit) {
                Mark m = l.pasted.computeIfAbsent(pad.id, k -> new Mark());
                m.atMs = atMs;
                m.by = by;
                m.kind = kind;
                m.run = run;
                m.layers++;
            }
            if (hit.isEmpty()) {
                Off o = new Off();
                o.x = off[0];
                o.y = off[1];
                o.atMs = atMs;
                o.by = by;
                o.kind = kind;
                l.offPad.add(o);
            }
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private int clean(Board b, Ledger l) {
        int n = 0;
        for (Pad p : b.pads) {
            n += l != null && (l.fixed.containsKey(p.id) || l.pasted.containsKey(p.id)) ? 0 : 1;
        }
        return n;
    }

    private void requireKnown(String board) {
        if (!boards.containsKey(board) && !data.boards.containsKey(board)) {
            throw new IllegalArgumentException("no board '" + board + "': a ledger board is a registered board file's "
                    + "name (ftp for ftp.board.xml), registered with Register board");
        }
    }

    /**
     * The board was wiped: {@code pads} (null: every pad) are clean again, the fixed ones stay fixed; a whole wipe takes
     * the off-pad paste too. A plan need it covers ends, and so does a run's wait. Returns the board's entry.
     */
    public synchronized Map<String, Object> wipe(String board, Collection<String> pads, String by, String note,
            long atMs) {
        requireKnown(board);
        Board b = boards.get(board);
        Ledger l = ledger(board);
        if (pads != null) {
            for (String id : pads) {
                if ((b == null || b.pads.stream().noneMatch(p -> p.id.equals(id))) && !l.pasted.containsKey(id)) {
                    throw new IllegalArgumentException("'" + id + "' is no pad of " + board);
                }
            }
        }
        Wipe w = new Wipe();
        w.atMs = atMs;
        w.by = by;
        w.note = note;
        w.pads = pads == null ? null : new ArrayList<>(pads);
        l.wipes.add(w);
        if (pads == null) {
            l.pasted.clear();
            l.offPad.clear();
        }
        else {
            l.pasted.keySet().removeAll(pads);
        }
        if (l.need != null && b != null && clean(b, l) >= l.need.pads) {
            l.need = null;
        }
        waiting.remove(board);
        save();
        return b == null ? null : entry(b);
    }

    /**
     * A fresh physical board took the board's seat (Next board): no used, fixed or off-pad pads any more (they were the
     * old board's), recorded as a wipe noted "fresh board"; its plan need stays. False when nothing was kept for it.
     */
    public synchronized boolean fresh(String board, String by, long atMs) {
        Ledger l = data.boards.get(board);
        if (l == null) {
            return false;
        }
        Wipe w = new Wipe();
        w.atMs = atMs;
        w.by = by;
        w.note = "fresh board";
        l.wipes.add(w);
        l.pasted.clear();
        l.fixed.clear();
        l.offPad.clear();
        waiting.remove(board);
        save();
        return true;
    }

    /** An agent's whole-plan need of clean pads on the board, shown until it clears; 0 clears it. */
    public synchronized void need(String board, int pads, String by, String what, long atMs) {
        requireKnown(board);
        if (pads < 0) {
            throw new IllegalArgumentException("'pads' must be 0 (clears the need) or more");
        }
        Need n = null;
        if (pads > 0) {
            n = new Need();
            n.pads = pads;
            n.by = by;
            n.what = what;
            n.atMs = atMs;
        }
        ledger(board).need = n;
        save();
    }

    /** A run on the board passed the budget: the plan need it was checked with is met, and nothing waits any more. */
    public synchronized void started(String board) {
        Ledger l = data.boards.get(board);
        if (l != null && l.need != null) {
            l.need = null;
            save();
        }
        waiting.remove(board);
    }

    /** The pads under {@code targets} (machine mm), per board, in the targets' order. */
    public synchronized Map<String, List<String>> padsAt(List<double[]> targets) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Board b : boards.values()) {
            Set<String> hit = new LinkedHashSet<>();
            for (double[] t : targets) {
                Pad p = b.holds(t[0], t[1]) ? b.padAt(t[0], t[1]) : null;
                if (p != null) {
                    hit.add(p.id);
                }
            }
            if (!hit.isEmpty()) {
                out.put(b.key, new ArrayList<>(hit));
            }
        }
        return out;
    }

    /**
     * What a run whose dots land at {@code targets} (machine mm) pastes: the board it pastes most pads on (a board it
     * runs short on first), its distinct pads there and the board's clean pads; null when it pastes no ledger board.
     */
    public synchronized Budget budget(List<double[]> targets) {
        Budget best = null;
        for (Map.Entry<String, List<String>> e : padsAt(targets).entrySet()) {
            Board b = boards.get(e.getKey());
            Ledger l = data.boards.get(b.key);
            Budget x = new Budget(b.key, b.name, e.getValue().size(), clean(b, l), l == null || l.need == null ? null
                    : l.need.pads);
            if (best == null || x.isShort() && !best.isShort() || x.isShort() == best.isShort() && x.needed > best.needed) {
                best = x;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------
    // A run that waits
    // ------------------------------------------------------------------------------------------

    public synchronized void waiting(String board, Waiting w) {
        waiting.put(board, w);
    }

    public synchronized Waiting waitingOf(String board) {
        return waiting.get(board);
    }

    /** The lock changed hands: a wait recorded under another holder's token is over. */
    public synchronized void forgetWaitingExcept(String token) {
        waiting.values().removeIf(w -> w.token != null && !w.token.equals(token));
    }

    // ------------------------------------------------------------------------------------------
    // Process's file
    // ------------------------------------------------------------------------------------------

    /**
     * Process's interim ledger ({@code runs/ledger_<board>.json}: wipes [{at, note}], fixed {pad: note}, pasted {pad: {at,
     * run, kind}}; local times) as the board's: its fixed pads, its wipes, and its pads pasted since its last wipe; the
     * engine's own dots stay unless that wipe came after them. Again later replaces what it imported before; refused
     * once the engine has recorded a wipe of its own (its ledger is then newer than any such file).
     */
    public synchronized void importProcess(String board, JsonObject f, long atMs) {
        List<Wipe> wipes = new ArrayList<>();
        long cut = Long.MIN_VALUE;
        for (JsonElement e : array(f, "wipes")) {
            JsonObject o = object(e, "a wipe");
            Wipe w = new Wipe();
            w.atMs = epochMs(o, "at");
            w.note = o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : null;
            w.imported = true;
            wipes.add(w);
            cut = Math.max(cut, w.atMs);
        }
        Map<String, String> fixed = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : object(f.get("fixed"), "fixed").entrySet()) {
            fixed.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
        }
        Map<String, Mark> pasted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : object(f.get("pasted"), "pasted").entrySet()) {
            JsonObject o = object(e.getValue(), "pasted " + e.getKey());
            Mark m = new Mark();
            m.atMs = epochMs(o, "at");
            m.by = "Process";
            m.kind = o.has("kind") && !o.get("kind").isJsonNull() ? o.get("kind").getAsString() : null;
            m.run = o.has("run") && !o.get("run").isJsonNull() ? o.get("run").getAsString() : null;
            m.layers = 1;
            if (m.atMs > cut) {
                pasted.put(e.getKey(), m);
            }
        }
        Ledger l = data.boards.get(board);
        for (Wipe w : l == null ? new ArrayList<Wipe>() : l.wipes) {
            if (!w.imported) {
                throw new IllegalStateException("the engine recorded a wipe of " + board + " at "
                        + ZCheck.clock(w.atMs) + ": its ledger is newer than Process's file");
            }
        }
        l = ledger(board);
        long since = cut;
        l.pasted.values().removeIf(m -> m.atMs <= since);
        l.offPad.removeIf(o -> o.atMs <= since);
        for (Map.Entry<String, Mark> e : pasted.entrySet()) {
            l.pasted.putIfAbsent(e.getKey(), e.getValue());
        }
        l.wipes = wipes;
        l.fixed = fixed;
        l.importedAtMs = atMs;
        save();
    }

    private static List<JsonElement> array(JsonObject f, String key) {
        List<JsonElement> l = new ArrayList<>();
        JsonElement e = f.get(key);
        if (e == null || e.isJsonNull()) {
            return l;
        }
        if (!e.isJsonArray()) {
            throw new IllegalArgumentException("'" + key + "' must be a list");
        }
        e.getAsJsonArray().forEach(l::add);
        return l;
    }

    private static JsonObject object(JsonElement e, String what) {
        if (e == null || e.isJsonNull()) {
            return new JsonObject();
        }
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("'" + what + "' must be an object");
        }
        return e.getAsJsonObject();
    }

    /** An ISO time: with an offset, or local (as Process's files write it). */
    private static long epochMs(JsonObject o, String key) {
        String at = o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        if (at == null) {
            throw new IllegalArgumentException("'" + key + "' (an ISO time) is missing in " + o);
        }
        try {
            return OffsetDateTime.parse(at).toInstant().toEpochMilli();
        }
        catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("'" + at + "' is no ISO time (2026-11-03T22:08:00)");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Views
    // ------------------------------------------------------------------------------------------

    /** status.padLedgers: one entry per registered board the engine keeps a ledger for. */
    public synchronized List<Map<String, Object>> status() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Board b : boards.values()) {
            if (data.boards.containsKey(b.key)) {
                out.add(entry(b));
            }
        }
        return out;
    }

    private Map<String, Object> entry(Board b) {
        Ledger l = data.boards.get(b.key);
        Map<String, int[]> sets = new TreeMap<>();
        for (Pad p : b.pads) {
            int[] s = sets.computeIfAbsent(p.set, k -> new int[2]);
            s[0]++;
            s[1] += l != null && (l.fixed.containsKey(p.id) || l.pasted.containsKey(p.id)) ? 0 : 1;
        }
        List<Map<String, Object>> sl = new ArrayList<>();
        for (Map.Entry<String, int[]> s : sets.entrySet()) {
            sl.add(map("set", s.getKey(), "total", s.getValue()[0], "clean", s.getValue()[1]));
        }
        Need n = l == null ? null : l.need;
        Waiting w = waiting.get(b.key);
        Wipe last = null;
        for (Wipe x : l == null ? new ArrayList<Wipe>() : l.wipes) {
            last = last == null || x.atMs > last.atMs ? x : last;
        }
        // check: the camera's re-check after a wipe is BR-76 stage 2; none runs yet
        return map("board", b.key, "name", b.name, "total", b.pads.size(), "clean", clean(b, l), "wipedAtMs",
                last == null ? null : last.atMs, "sets", sl, "check", null, "need", n == null ? null
                        : map("pads", n.pads, "by", n.by, "what", n.what, "atMs", n.atMs),
                "waiting", w == null ? null : map("by", w.by, "what", w.what, "clean", w.clean, "needed", w.needed,
                        "atMs", w.atMs));
    }

    /**
     * GET /api/boards/{board}/pads: the board's entry, its drawing ({@code view}), every pad with its state (clean, used,
     * fixed), the last dot's time, holder, kind and run, its layers, and the off-pad dots; null for a board not known.
     */
    public synchronized Map<String, Object> pads(String board) {
        Board b = boards.get(board);
        if (b == null) {
            return null;
        }
        Ledger l = data.boards.get(board);
        Map<String, Object> out = entry(b);
        out.put("view", b.view);
        List<Map<String, Object>> pads = new ArrayList<>();
        for (Pad p : b.pads) {
            Mark m = l == null ? null : l.pasted.get(p.id);
            String note = l == null ? null : l.fixed.get(p.id);
            boolean fixed = l != null && l.fixed.containsKey(p.id);
            pads.add(map("id", p.id, "set", p.set, "state", fixed ? "fixed" : m != null ? "used" : "clean", "atMs",
                    m == null ? null : m.atMs, "by", m == null ? null : m.by, "kind", m == null ? null : m.kind, "run",
                    m == null ? null : m.run, "note", note, "layers", m == null ? 0 : m.layers, "x", r3(p.x), "y",
                    r3(p.y), "w", r3(p.w), "h", r3(p.h), "rotation", r3(p.rotation)));
        }
        out.put("pads", pads);
        List<Map<String, Object>> off = new ArrayList<>();
        for (Off o : l == null ? new ArrayList<Off>() : l.offPad) {
            off.add(map("x", r3(o.x), "y", r3(o.y), "atMs", o.atMs, "by", o.by, "kind", o.kind));
        }
        out.put("offPad", off);
        return out;
    }

    private static double r3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}
