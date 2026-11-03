package org.openpnp.instancelabs.padprofile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;

/**
 * The key of a paste opening for grouping (§3.2): package, long side L, short side S, roundness.
 * An opening joins the first group of its package within 0.02 mm on L and S and 5 % roundness; the
 * group id hashes the first opening's key, so the preview, the board file and the plan of the same
 * package give the same id.
 */
public final class GroupKey {
    /** Natural order of pad names and refs: "2" before "10", "A2" before "A10". */
    public static final Comparator<String> NATURAL = GroupKey::compareNatural;

    public final String packageId;
    public final double longMm;
    public final double shortMm;
    public final double roundness;

    public GroupKey(String packageId, double wMm, double hMm, double roundness) {
        this.packageId = packageId == null ? "" : packageId;
        this.longMm = Math.max(wMm, hMm);
        this.shortMm = Math.min(wMm, hMm);
        this.roundness = roundness;
    }

    public static GroupKey of(PadInstance p) {
        return new GroupKey(p.packageId, p.wMm, p.hMm, p.roundness);
    }

    /** True if an opening with key {@code o} joins the group started by this key. */
    public boolean joins(GroupKey o) {
        return packageId.equals(o.packageId) && Math.abs(longMm - o.longMm) <= Limits.GROUP_TOL_MM + Limits.EPS
                && Math.abs(shortMm - o.shortMm) <= Limits.GROUP_TOL_MM + Limits.EPS
                && Math.abs(roundness - o.roundness) <= Limits.GROUP_TOL_ROUNDNESS + Limits.EPS;
    }

    /** "packageId|L|S|roundness", L and S to 0.001 mm, roundness to 0.1. */
    public String text() {
        return String.format(Locale.ROOT, "%s|%.3f|%.3f|%.1f", packageId, longMm, shortMm, roundness);
    }

    /** "g" + 12 hex digits of SHA-1 of {@link #text()} (and of {@code extra} when a group splits by type). */
    public String id(String extra) {
        return "g" + sha12(extra == null ? text() : text() + "|" + extra);
    }

    /** The first 12 hex digits of SHA-1. */
    public static String sha12(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                b.append(String.format(Locale.ROOT, "%02x", d[i] & 0xff));
            }
            return b.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A package id without the "~n" a second variant of a footprint gets (BI §3.5). */
    public static String basePackage(String packageId) {
        if (packageId == null) {
            return "";
        }
        return packageId.replaceFirst("~\\d+$", "");
    }

    public static int compareNatural(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
                continue;
            }
            if (ca != cb) {
                return Character.compare(ca, cb);
            }
            i++;
            j++;
        }
        int c = Integer.compare(a.length() - i, b.length() - j);
        return c != 0 ? c : a.compareTo(b);
    }
}
