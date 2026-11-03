package org.openpnp.instancelabs.kicad;

/**
 * The one cleaning function for every string from a KiCad file that reaches OpenPnP or the board art (design §2.2).
 * simple-xml writes characters that XML forbids (and then cannot read the file back), and turns line breaks and tabs
 * into spaces on the way back, so ids and texts are cleaned before they are used.
 */
public final class KText {
    public static final int ID_MAX = 100;

    private KText() {
    }

    /**
     * TAB, CR and LF become a space; other C0 controls, DEL, C1, U+FFFE, U+FFFF and lone surrogates are dropped;
     * whitespace runs collapse to one space; the result is trimmed and capped at maxCodePoints code points.
     */
    public static String clean(String s, int maxCodePoints) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean pendingSpace = false;
        int count = 0;
        for (int i = 0; i < s.length() && count < maxCodePoints;) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\t' || cp == '\r' || cp == '\n') {
                cp = ' ';
            }
            if (dropped(cp)) {
                continue;
            }
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = sb.length() > 0;
                continue;
            }
            if (pendingSpace) {
                if (count + 1 >= maxCodePoints) {
                    break;
                }
                sb.append(' ');
                count++;
                pendingSpace = false;
            }
            sb.appendCodePoint(cp);
            count++;
        }
        return sb.toString();
    }

    /** clean(s, 100); an empty result becomes ifEmpty. */
    public static String cleanId(String s, String ifEmpty) {
        String c = clean(s, ID_MAX);
        return c.isEmpty() ? ifEmpty : c;
    }

    /** What cleaning changed, for the ID_CLEANED text ("a line break", "a control character", …). */
    static String whatChanged(String raw, int maxCodePoints) {
        if (raw == null) {
            return "nothing";
        }
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            return "a line break";
        }
        if (raw.indexOf('\t') >= 0) {
            return "a tab";
        }
        for (int i = 0; i < raw.length();) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (dropped(cp)) {
                return "a character that is not text";
            }
        }
        if (raw.codePointCount(0, raw.length()) > maxCodePoints) {
            return "more than " + maxCodePoints + " characters";
        }
        return "extra spaces";
    }

    private static boolean dropped(int cp) {
        return cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || cp == 0xFFFE || cp == 0xFFFF
                || (cp >= 0xD800 && cp <= 0xDFFF);
    }
}
