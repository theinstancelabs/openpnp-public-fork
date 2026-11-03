package org.openpnp.instancelabs.kicad;

/**
 * A KiCad file that cannot be read (design §2.2, §3.2). The message is the text for the operator; {@link #detail}
 * is the bare reason (for logs and tests).
 */
public class SExprException extends Exception {
    private static final long serialVersionUID = 1L;

    public static final String SYNTAX = "syntax";
    public static final String LIMIT = "limit";
    public static final String NOT_KICAD = "not-kicad";

    /** Added to every limit text (and to the upload's 413 text). */
    public static final String UNFILL_HINT = "A board with filled zones is much larger: in KiCad, Edit › Unfill All "
            + "Zones, save, and upload that file (zones are then not drawn).";

    public final String kind;
    public final int line;
    public final int column;
    public final String detail;

    private SExprException(String kind, int line, int column, String detail, String message) {
        super(message);
        this.kind = kind;
        this.line = line;
        this.column = column;
        this.detail = detail;
    }

    public static SExprException syntax(int line, int column, String what) {
        return new SExprException(SYNTAX, line, column, what, "Line " + line + ", column " + column + ": " + what
                + ". The file may be cut short or not saved by KiCad.");
    }

    public static SExprException limit(int line, String what) {
        return new SExprException(LIMIT, line, 0, what,
                "The board file is too complex to read here: " + what + ". " + UNFILL_HINT);
    }

    public static SExprException notKicad(String text) {
        return new SExprException(NOT_KICAD, 1, 1, text, text);
    }
}
