package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * One list of a KiCad S-expression: its head (the first atom) and its items after the head (atoms as String, lists as
 * SExpr). Quoted strings are atoms too; {@link #flag} only matches unquoted ones.
 */
public final class SExpr {
    public final String head;
    public final List<Object> items;
    public final int line;
    private final BitSet quoted;

    SExpr(String head, List<Object> items, BitSet quoted, int line) {
        this.head = head;
        this.items = Collections.unmodifiableList(items);
        this.quoted = quoted;
        this.line = line;
    }

    public int size() {
        return items.size();
    }

    /** The first list child with this head, or null. */
    public SExpr child(String h) {
        for (Object o : items) {
            if (o instanceof SExpr && h.equals(((SExpr) o).head)) {
                return (SExpr) o;
            }
        }
        return null;
    }

    public List<SExpr> children(String h) {
        List<SExpr> l = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof SExpr && h.equals(((SExpr) o).head)) {
                l.add((SExpr) o);
            }
        }
        return l;
    }

    /** Every list child, in file order. */
    public List<SExpr> lists() {
        List<SExpr> l = new ArrayList<>();
        for (Object o : items) {
            if (o instanceof SExpr) {
                l.add((SExpr) o);
            }
        }
        return l;
    }

    /** Item i if it is an atom, else null (also when missing). */
    public String atom(int i) {
        if (i < 0 || i >= items.size()) {
            return null;
        }
        Object o = items.get(i);
        return o instanceof String ? (String) o : null;
    }

    public boolean isQuoted(int i) {
        return quoted != null && quoted.get(i);
    }

    /** Item i as a number; missing, unparsable or non-finite gives def. */
    public double num(int i, double def) {
        String a = atom(i);
        if (a == null) {
            return def;
        }
        try {
            double v = Double.parseDouble(a);
            return Double.isFinite(v) ? v : def;
        }
        catch (NumberFormatException e) {
            return def;
        }
    }

    /** A bare (unquoted) atom such as locked or hide among the items. */
    public boolean flag(String bare) {
        for (int i = 0; i < items.size(); i++) {
            if (!isQuoted(i) && bare.equals(items.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** (h yes) / (h) / the bare atom h → true; (h no) → false; absent → false. */
    public boolean yes(String h) {
        SExpr c = child(h);
        if (c != null) {
            String a = c.atom(0);
            return a == null || "yes".equals(a) || "true".equals(a);
        }
        return flag(h);
    }

    /** The first atom of the child h, or null. */
    public String str(String h) {
        SExpr c = child(h);
        return c == null ? null : c.atom(0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(").append(head);
        for (Object o : items) {
            sb.append(' ').append(o);
            if (sb.length() > 200) {
                sb.append(" …");
                break;
            }
        }
        return sb.append(')').toString();
    }
}
