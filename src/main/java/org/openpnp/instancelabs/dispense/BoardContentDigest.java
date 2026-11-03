package org.openpnp.instancelabs.dispense;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.openpnp.model.Board;
import org.openpnp.model.Footprint;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * What a plan takes from a board besides the placements themselves: per placement the part and
 * package ids, per distinct package its footprint (units, and each pad's name, x, y, w, h, rotation
 * and roundness at 4 decimals). The board fingerprint appends it, so a board re-imported with other
 * pads or parts makes the plans of the old one stale (the placements alone do not show it).
 */
public final class BoardContentDigest {
    private BoardContentDigest() {
    }

    /** First 16 hex digits of the SHA-256 of the board's part ids, package ids and footprints. */
    public static String of(Board board) {
        StringBuilder sb = new StringBuilder("content;");
        // by id: the order of OpenPnP's package and part lists never matters
        Map<String, Package> packages = new TreeMap<>();
        if (board != null) {
            for (Placement pl : board.getPlacements()) {
                Part part = pl.getPart();
                Package pkg = part == null ? null : part.getPackage();
                sb.append('|').append(pl.getId()).append(',').append(part == null ? "-" : part.getId()).append(',')
                        .append(pkg == null ? "-" : pkg.getId());
                if (pkg != null) {
                    packages.put(pkg.getId(), pkg);
                }
            }
        }
        for (Map.Entry<String, Package> e : packages.entrySet()) {
            sb.append("|package ").append(e.getKey()).append('=').append(footprint(e.getValue()));
        }
        return sha16(sb.toString());
    }

    /** Digest of a package's footprint: its units and every pad, in footprint order. */
    static String footprint(Package pkg) {
        Footprint fp = pkg.getFootprint();
        StringBuilder sb = new StringBuilder();
        if (fp != null) {
            sb.append(fp.getUnits());
            for (Footprint.Pad p : fp.getPads()) {
                sb.append(String.format(Locale.ROOT, ";%s,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f", p.getName(), p.getX(),
                        p.getY(), p.getWidth(), p.getHeight(), p.getRotation(), p.getRoundness()));
            }
        }
        return sha16(sb.toString());
    }

    private static String sha16(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", h[i]));
            }
            return hex.toString();
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java runtime has SHA-256
        }
    }
}
