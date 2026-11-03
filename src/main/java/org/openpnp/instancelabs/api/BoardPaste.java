package org.openpnp.instancelabs.api;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.openpnp.instancelabs.dispense.DispenseListener;
import org.openpnp.instancelabs.dispense.RunResult;

/**
 * The paste on each seated board, kept by the server so every tab and browser sees the same board (rc1 review: only
 * the browser tab kept it, so a new tab took a pasted board for a clean one and offered a whole run on it). A board is
 * the seat of its plan's source: a job board location, or a board file at its X/Y/rotation (a plan made again for the
 * same seat is the same board, UI_DESIGN §8.1.7). Each dot of a board run counts on its pad as it is laid, and the board
 * gets the next number of this session at its first dot. "Next board" takes the boards off the machine
 * ({@link #nextBoard}); a run the operator starts on a wiped board clears its seat first ({@link #wiped}). Memory only:
 * an OpenPnP restart forgets it, as it forgets the plans. Dots arrive on the machine thread, views on API threads.
 */
final class BoardPaste implements DispenseListener {
    /** One seated board with paste. */
    static final class Board {
        final int number;
        final long sinceMs;
        /** Pad id -> the dots laid on it since the board was seated. */
        final Map<String, Integer> pads = new LinkedHashMap<>();
        int dots;

        Board(int number, long sinceMs) {
            this.number = number;
            this.sinceMs = sinceMs;
        }
    }

    private final PlanStore plans;
    private final Map<String, Board> boards = new LinkedHashMap<>();
    /** Run id -> the number of the board its dots went on (the last 50 board runs). */
    private final Map<String, Integer> runBoards = new LinkedHashMap<String, Integer>(16, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 50;
        }
    };
    /** Boards numbered this session. */
    private int count;
    /** Bumped when a board is numbered, taken off or wiped: another tab reads its plan again then. */
    private int version;

    BoardPaste(PlanStore plans) {
        this.plans = plans;
    }

    /** The seat of a plan source: "job:<board location id>" or "file:<file>|x|y|rotation" (the web UI's seatKey). */
    static String seat(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        if ("job".equals(source.get("source"))) {
            return "job:" + source.get("boardLocationId");
        }
        return String.format(Locale.ROOT, "file:%s|%.3f|%.3f|%.2f", source.get("file"), num(source.get("x")),
                num(source.get("y")), num(source.get("rotation")));
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }

    @Override
    public synchronized void onDot(RunResult run, RunResult.DotTiming t, int done, int total) {
        if (!"board".equals(run.kind) || t.padId == null) {
            return;
        }
        PlanStore.Entry e = plans.get(run.planId);
        String seat = e == null ? null : seat(e.source);
        if (seat == null) {
            return;
        }
        Board b = boards.get(seat);
        if (b == null) {
            b = new Board(++count, System.currentTimeMillis());
            boards.put(seat, b);
            version++;
        }
        b.pads.merge(t.padId, 1, Integer::sum);
        b.dots++;
        runBoards.put(run.runId, b.number);
    }

    /**
     * The board on a plan's seat: {board: its number, or null when it has no paste yet, nextBoard: the number a new board
     * gets, sinceMs, dots, pads: {padId: dots laid}}.
     */
    synchronized Map<String, Object> view(Map<String, Object> source) {
        Board b = boards.get(seat(source));
        return Views.map("board", b == null ? null : b.number, "nextBoard", count + 1, "sinceMs", b == null ? null
                : b.sinceMs, "dots", b == null ? 0 : b.dots, "pads", b == null ? new LinkedHashMap<String, Integer>()
                        : new LinkedHashMap<>(b.pads));
    }

    /** "Next board": the boards on the machine are taken off; the next dot starts a new one. Returns its number. */
    synchronized int nextBoard() {
        boards.clear();
        version++;
        return count + 1;
    }

    /** The operator wiped the board on this seat before a run: its paste is gone (its number stays). */
    synchronized void wiped(Map<String, Object> source) {
        Board b = boards.get(seat(source));
        if (b != null) {
            b.pads.clear();
            b.dots = 0;
            version++;
        }
    }

    /** Changes when a board is numbered, taken off or wiped (status.dispense.pasteVersion). */
    synchronized int version() {
        return version;
    }

    /** The number of the board a run's dots went on, or null (no dot, or not a board run of this session). */
    synchronized Integer boardOfRun(String runId) {
        return runId == null ? null : runBoards.get(runId);
    }
}
