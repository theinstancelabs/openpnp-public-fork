package org.openpnp.instancelabs.board;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Package;
import org.openpnp.model.Part;

/**
 * The package and part ids of one import (design §3.5), against one snapshot of OpenPnP's lists. OpenPnP keys both
 * lists case-insensitively and silently replaces an id that is added again, so every id is checked against one
 * case-insensitive set of what OpenPnP has and what this import chose. An existing package is reused only when its
 * footprint matches the file's; it is never changed (other boards' plans depend on its pads): the file's package then
 * gets the next free {@code ~2} … {@code ~99}. Parts are keyed by (package, value).
 */
public final class IdAllocator {
    public static final int MAX_SUFFIX = 99;
    public static final String NEW = "new";
    public static final String REUSE = "reuse";
    public static final String SUFFIXED = "suffixed";
    /** Footprint match: positions and sizes (mm), rotation (degrees), roundness (%). */
    static final double MM_TOL = 0.01;
    static final double DEG_TOL = 0.01;
    static final double ROUNDNESS_TOL = 0.5;

    private IdAllocator() {
    }

    /** OpenPnP's packages (id and pads in mm) and parts (id and package id) at one moment. No OpenPnP objects. */
    public static final class Snapshot {
        final Map<String, List<PadP>> packages = new HashMap<>();
        final Map<String, String> packageIds = new HashMap<>();
        /** Upper-cased part id → its package id (null when it has none). */
        final Map<String, String> parts = new HashMap<>();
        final Map<String, String> partIds = new HashMap<>();

        /** Read OpenPnP's lists; call where they may be read ({@link EdtRunner}). */
        public static Snapshot of(Configuration c) {
            Snapshot s = new Snapshot();
            for (Package p : c.getPackages()) {
                List<PadP> pads = new ArrayList<>();
                Footprint fp = p.getFootprint();
                if (fp != null && fp.getPads() != null) {
                    double f = new Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
                    for (Footprint.Pad pad : fp.getPads()) {
                        PadP x = new PadP();
                        x.name = pad.getName();
                        x.x = pad.getX() * f;
                        x.y = pad.getY() * f;
                        x.w = pad.getWidth() * f;
                        x.h = pad.getHeight() * f;
                        x.rotation = pad.getRotation();
                        x.roundness = pad.getRoundness();
                        pads.add(x);
                    }
                }
                s.addPackage(p.getId(), pads);
            }
            for (Part p : c.getParts()) {
                s.addPart(p.getId(), p.getPackage() == null ? null : p.getPackage().getId());
            }
            return s;
        }

        public Snapshot addPackage(String id, List<PadP> padsMm) {
            packages.put(key(id), padsMm == null ? Collections.emptyList() : padsMm);
            packageIds.put(key(id), id);
            return this;
        }

        public Snapshot addPart(String id, String packageId) {
            parts.put(key(id), packageId);
            partIds.put(key(id), id);
            return this;
        }
    }

    /** The id of one package of the proposal (same index as {@code proposal.packages}). */
    public static final class PackageId {
        public final int index;
        public final String id;
        /** new | reuse | suffixed (a new package under a suffixed id). */
        public final String state;
        /** Why the id is not the footprint's own name, or why an existing package is reused; null for a plain new. */
        public final String why;

        PackageId(int index, String id, String state, String why) {
            this.index = index;
            this.id = id;
            this.state = state;
            this.why = why;
        }

        /** The save adds this package to OpenPnP. */
        public boolean creates() {
            return !REUSE.equals(state);
        }
    }

    /** One part: (package id, value), in the order its first placement comes in the file. */
    public static final class PartId {
        public final String id;
        public final String packageId;
        public final String value;
        public final String state;
        public final List<String> refs = new ArrayList<>();

        PartId(String id, String packageId, String value, String state) {
            this.id = id;
            this.packageId = packageId;
            this.value = value;
            this.state = state;
        }

        public boolean creates() {
            return !REUSE.equals(state);
        }
    }

    public static final class Result {
        public final List<PackageId> packages = new ArrayList<>();
        public final List<PartId> parts = new ArrayList<>();
        /** Placement index (proposal order) → index into {@link #parts}. */
        public final List<Integer> placementPart = new ArrayList<>();

        /**
         * The first 16 hex digits of the SHA-256 of the ordered (package id, state) and (part id, state) lists: the
         * preview sends it, and a save whose allocation differs is refused (OpenPnP's lists changed meanwhile).
         */
        public String decisions() {
            StringBuilder sb = new StringBuilder();
            for (PackageId p : packages) {
                sb.append("package|").append(p.id).append('|').append(p.state).append('\n');
            }
            for (PartId p : parts) {
                sb.append("part|").append(p.id).append('|').append(p.state).append('\n');
            }
            return BoardArt.sha256Hex(sb.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        }

        public PartId partOf(int placementIndex) {
            return parts.get(placementPart.get(placementIndex));
        }
    }

    /** Thrown when no id up to {@code ~99} is free for a package or part. */
    public static final class Exhausted extends Exception {
        private static final long serialVersionUID = 1L;
        public final String baseId;

        Exhausted(String baseId, String what) {
            super(baseId + " already has " + MAX_SUFFIX + " variants in OpenPnP: remove unused " + what
                    + " in OpenPnP's " + Character.toUpperCase(what.charAt(0)) + what.substring(1) + " tab.");
            this.baseId = baseId;
        }
    }

    public static Result allocate(ImportProposal p, Snapshot s) throws Exhausted {
        Result r = new Result();
        Set<String> chosen = new HashSet<>();
        for (int i = 0; i < p.packages.size(); i++) {
            r.packages.add(pkg(i, p.packages.get(i), s, chosen));
        }
        Map<String, Integer> byKey = new LinkedHashMap<>();
        Set<String> chosenParts = new HashSet<>();
        for (PlacementP pl : p.placements) {
            PackageId pk = r.packages.get(pl.packageIndex);
            String value = pl.value == null ? "" : pl.value;
            String k = key(pk.id) + '\u0000' + value;
            Integer at = byKey.get(k);
            if (at == null) {
                at = r.parts.size();
                r.parts.add(part(pk.id, value, s, chosenParts));
                byKey.put(k, at);
            }
            r.parts.get(at).refs.add(pl.id);
            r.placementPart.add(at);
        }
        return r;
    }

    private static PackageId pkg(int index, PackageP pk, Snapshot s, Set<String> chosen) throws Exhausted {
        String why = null;
        for (int k = 1; k <= MAX_SUFFIX; k++) {
            String cand = candidate(pk.baseId, k);
            String key = key(cand);
            if (chosen.contains(key)) {
                if (why == null) {
                    why = "another footprint in this file is also called " + pk.baseId;
                }
                continue;
            }
            List<PadP> existing = s.packages.get(key);
            if (existing != null) {
                if (sameFootprint(existing, pk.pads)) {
                    chosen.add(key);
                    return new PackageId(index, s.packageIds.get(key), REUSE, s.packageIds.get(key)
                            + " exists in OpenPnP with the same pads: used as it is");
                }
                if (why == null) {
                    why = s.packageIds.get(key) + " exists in OpenPnP " + (existing.isEmpty() ? "without pads"
                            : "with other pads");
                }
                continue;
            }
            chosen.add(key);
            return k == 1 ? new PackageId(index, cand, NEW, null) : new PackageId(index, cand, SUFFIXED, why);
        }
        throw new Exhausted(pk.baseId, "packages");
    }

    private static PartId part(String packageId, String value, Snapshot s, Set<String> chosen) throws Exhausted {
        String base = value.isEmpty() ? packageId : packageId + "-" + value;
        for (int k = 1; k <= MAX_SUFFIX; k++) {
            String cand = candidate(base, k);
            String key = key(cand);
            if (chosen.contains(key)) {
                continue; // another (package, value) of this import has it
            }
            if (s.parts.containsKey(key)) {
                String pkg = s.parts.get(key);
                if (pkg != null && key(pkg).equals(key(packageId))) {
                    chosen.add(key);
                    return new PartId(s.partIds.get(key), packageId, value, REUSE);
                }
                continue; // a part of another package
            }
            chosen.add(key);
            return new PartId(cand, packageId, value, k == 1 ? NEW : SUFFIXED);
        }
        throw new Exhausted(base, "parts");
    }

    static String candidate(String base, int k) {
        return k == 1 ? base : base + "~" + k;
    }

    /**
     * OpenPnP's own key ({@code Configuration.getPackage}/{@code getPart} upper-case with the default locale): two ids
     * that OpenPnP would take for one are one here too.
     */
    static String key(String id) {
        return id.toUpperCase();
    }

    /**
     * Same pad count and names; per name x, y, w and h within 0.01 mm, rotation within 0.01° and roundness within
     * 0.5 %.
     */
    static boolean sameFootprint(List<PadP> a, List<PadP> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, List<PadP>> byName = new HashMap<>();
        for (PadP p : a) {
            byName.computeIfAbsent(p.name == null ? "" : p.name, n -> new ArrayList<>()).add(p);
        }
        for (PadP q : b) {
            List<PadP> l = byName.get(q.name == null ? "" : q.name);
            PadP match = null;
            if (l != null) {
                for (PadP p : l) {
                    if (samePad(p, q)) {
                        match = p;
                        break;
                    }
                }
            }
            if (match == null) {
                return false;
            }
            l.remove(match);
        }
        return true;
    }

    private static boolean samePad(PadP p, PadP q) {
        double dr = Math.abs(((p.rotation - q.rotation) % 360 + 540) % 360 - 180);
        return Math.abs(p.x - q.x) <= MM_TOL && Math.abs(p.y - q.y) <= MM_TOL && Math.abs(p.w - q.w) <= MM_TOL
                && Math.abs(p.h - q.h) <= MM_TOL && dr <= DEG_TOL && Math.abs(p.roundness - q.roundness) <= ROUNDNESS_TOL;
    }
}
