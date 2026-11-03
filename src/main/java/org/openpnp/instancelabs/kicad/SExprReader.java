package org.openpnp.instancelabs.kicad;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Bytes of a .kicad_pcb file → an SExpr tree, under limits (design §2.2). Defensive: a size, depth, item, atom and
 * time limit; subtrees that carry nothing for us (3-D model paths, embedded files, render caches) are scanned without
 * building nodes, and nothing from the file is ever used as a path.
 */
public final class SExprReader {
    public static final class Limits {
        public int maxBytes = 12 * 1024 * 1024;
        public int maxDepth = 64;
        public int maxItems = 1_500_000;
        public int maxAtomChars = 1024 * 1024;
    }

    /** Heads whose subtrees are read to their closing parenthesis without building nodes. */
    public static final Set<String> SKIPPED_HEADS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("model", "embedded_files", "render_cache", "image", "group", "generated")));

    private static final class Frame {
        final int line;
        String head;
        boolean headSet;
        final List<Object> items = new ArrayList<>();
        BitSet quoted;

        Frame(int line) {
            this.line = line;
        }

        void add(Object o, boolean q) {
            if (q) {
                if (quoted == null) {
                    quoted = new BitSet();
                }
                quoted.set(items.size());
            }
            items.add(o);
        }

        SExpr build() {
            return new SExpr(head, items, quoted, line);
        }
    }

    private final String s;
    private final int n;
    private final Limits lim;
    private final Deadline deadline;
    private int pos;
    private int line = 1;
    private int lineStart;
    private long itemCount;
    private long tokens;

    private SExprReader(String s, Limits lim, Deadline deadline) {
        this.s = s;
        this.n = s.length();
        this.lim = lim;
        this.deadline = deadline;
    }

    public static SExpr read(byte[] bytes, Deadline deadline) throws SExprException {
        return read(bytes, new Limits(), deadline);
    }

    /** UTF-8 (malformed bytes become U+FFFD), an optional BOM, then one (kicad_pcb …) list. */
    public static SExpr read(byte[] bytes, Limits limits, Deadline deadline) throws SExprException {
        if (bytes.length > limits.maxBytes) {
            throw SExprException.limit(0, "larger than " + limits.maxBytes / (1024 * 1024) + " MB");
        }
        return new SExprReader(new String(bytes, StandardCharsets.UTF_8), limits, deadline).parse();
    }

    private SExpr parse() throws SExprException {
        if (n > 0 && s.charAt(0) == '\uFEFF') {
            pos = 1;
            lineStart = 1;
        }
        checkRoot();
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        int skip = 0; // > 0: depth inside a skipped subtree
        while (true) {
            skipWhitespace();
            if (pos >= n) {
                Frame open = stack.peek();
                throw SExprException.syntax(line, column(),
                        "the file ends before the list opened at line " + (open == null ? line : open.line)
                                + " is closed");
            }
            if ((++tokens & 0xFFFF) == 0) {
                deadline.check();
            }
            char c = s.charAt(pos);
            if (c == '(') {
                if (stack.size() + skip + 1 > lim.maxDepth) {
                    throw SExprException.limit(line, "nested deeper than " + lim.maxDepth + " levels at line " + line);
                }
                pos++;
                if (skip > 0) {
                    skip++;
                }
                else {
                    stack.push(new Frame(line));
                    countItem();
                }
                continue;
            }
            if (c == ')') {
                pos++;
                if (skip > 0) {
                    skip--;
                    continue;
                }
                if (stack.isEmpty()) {
                    throw SExprException.syntax(line, column() - 1, "a ')' closes a list that was never opened");
                }
                SExpr e = stack.pop().build();
                if (stack.isEmpty()) {
                    return e; // text after the board's closing parenthesis is ignored
                }
                stack.peek().add(e, false);
                continue;
            }
            boolean quoted = c == '"';
            boolean keep = skip == 0;
            String a = quoted ? readString(keep) : readAtom(keep);
            if (!keep) {
                continue;
            }
            Frame top = stack.peek();
            if (top == null) {
                throw SExprException.syntax(line, column(), "text outside any list");
            }
            countItem();
            if (!top.headSet && top.items.isEmpty()) {
                top.headSet = true;
                top.head = a;
                if (stack.size() > 1 && !quoted && SKIPPED_HEADS.contains(a)) {
                    stack.pop();
                    itemCount -= 2; // a skipped subtree costs no nodes
                    skip = 1;
                }
                continue;
            }
            top.add(a, quoted);
        }
    }

    /** The first token must be ( followed by kicad_pcb; anything else is answered with what the file is. */
    private void checkRoot() throws SExprException {
        int i = pos;
        while (i < n && isWhitespace(s.charAt(i))) {
            i++;
        }
        String start = KText.clean(s.substring(i, Math.min(n, i + 20)), 20);
        if (i >= n) {
            throw SExprException.notKicad("Not a KiCad board file (it is empty): choose a .kicad_pcb file (KiCad 5 to 10).");
        }
        String head = null;
        if (s.charAt(i) == '(') {
            int j = i + 1;
            while (j < n && isWhitespace(s.charAt(j))) {
                j++;
            }
            int k = j;
            while (k < n && !isDelimiter(s.charAt(k))) {
                k++;
            }
            head = s.substring(j, k);
        }
        if ("kicad_pcb".equals(head)) {
            return;
        }
        if ("footprint".equals(head) || "module".equals(head)) {
            throw SExprException.notKicad(
                    "This is a KiCad footprint (.kicad_mod), not a board: choose the board's .kicad_pcb file.");
        }
        if ("kicad_sch".equals(head)) {
            throw SExprException.notKicad(
                    "This is a KiCad schematic, not a board: choose the board's .kicad_pcb file.");
        }
        throw SExprException.notKicad("Not a KiCad board file (it starts with \"" + start
                + "\"): choose a .kicad_pcb file (KiCad 5 to 10).");
    }

    private String readAtom(boolean keep) throws SExprException {
        int start = pos;
        while (pos < n && !isDelimiter(s.charAt(pos))) {
            pos++;
        }
        if (!keep) {
            return null;
        }
        if (pos - start > lim.maxAtomChars) {
            throw tooLong();
        }
        return s.substring(start, pos);
    }

    /** "…" with the escapes \" \\ \n \r \t; any other escaped character is kept as written (backslash included). */
    private String readString(boolean keep) throws SExprException {
        int startLine = line;
        int startColumn = column();
        pos++;
        int from = pos;
        StringBuilder sb = null;
        while (true) {
            if (pos >= n) {
                throw SExprException.syntax(startLine, startColumn, "a quoted text is not closed");
            }
            char c = s.charAt(pos);
            if (c == '"') {
                break;
            }
            if (c == '\n') {
                line++;
                lineStart = pos + 1;
            }
            if (c == '\\' && pos + 1 < n) {
                if (keep) {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, from, pos);
                    char e = s.charAt(pos + 1);
                    switch (e) {
                        case '"':
                        case '\\':
                            sb.append(e);
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        default:
                            sb.append('\\').append(e);
                            break;
                    }
                }
                if (s.charAt(pos + 1) == '\n') {
                    line++;
                    lineStart = pos + 2;
                }
                pos += 2;
                from = pos;
                continue;
            }
            pos++;
            if (keep && pos - from + (sb == null ? 0 : sb.length()) > lim.maxAtomChars) {
                line = startLine;
                throw tooLong();
            }
        }
        String out = null;
        if (keep) {
            out = sb == null ? s.substring(from, pos) : sb.append(s, from, pos).toString();
            if (out.length() > lim.maxAtomChars) {
                line = startLine;
                throw tooLong();
            }
        }
        pos++; // closing quote
        return out;
    }

    private SExprException tooLong() {
        String size = lim.maxAtomChars % (1024 * 1024) == 0 ? lim.maxAtomChars / (1024 * 1024) + " MiB"
                : String.format(Locale.ROOT, "%,d characters", lim.maxAtomChars);
        return SExprException.limit(line, "a value longer than " + size + " at line " + line);
    }

    private void countItem() throws SExprException {
        if (++itemCount > lim.maxItems) {
            throw SExprException.limit(line, String.format(Locale.ROOT, "more than %,d items", lim.maxItems));
        }
    }

    private void skipWhitespace() {
        while (pos < n) {
            char c = s.charAt(pos);
            if (c == '\n') {
                line++;
                lineStart = pos + 1;
            }
            else if (c != ' ' && c != '\t' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    private int column() {
        return pos - lineStart + 1;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isDelimiter(char c) {
        return isWhitespace(c) || c == '(' || c == ')' || c == '"';
    }
}
