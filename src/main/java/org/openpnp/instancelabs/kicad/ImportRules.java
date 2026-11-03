package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.kicad.ImportProposal.FootprintP;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;
import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Graphic;

/**
 * KiCad board + frame → {@link ImportProposal} (design §2.7–§2.9): classifies every footprint (first matching rule
 * wins), takes the paste apertures, names placements and pads uniquely, and groups footprints into package variants.
 * Do-not-place footprints get no paste unless the operator asks for it (main-thread decision, §13 item 1); footprints
 * with a paste opening outside the board's box get none (OFF_BOARD).
 */
public final class ImportRules {
    private static final Pattern FID_REF = Pattern.compile("(?i)^FID");
    private static final Pattern FID_NAME = Pattern.compile("(?i)^fiducial");
    private static final Pattern DNP_VALUE = Pattern.compile("(?i)^(DNP|DNF|NP|DO NOT PLACE)$");
    /** A paste opening may reach this far past the board's box (rounding) and still be on the board. */
    private static final double EDGE_TOL_MM = 0.001;

    private final boolean fiducialNameRule;
    private final boolean pasteDnp;

    /** The defaults: footprint names starting with "Fiducial" are fiducials; do-not-place parts get no paste. */
    public ImportRules() {
        this(true, false);
    }

    /**
     * @param fiducialNameRule footprint names starting with "Fiducial" are fiducials too (§13 item 2)
     * @param pasteDnp do-not-place footprints with paste become disabled placements (which the engine pastes)
     */
    public ImportRules(boolean fiducialNameRule, boolean pasteDnp) {
        this.fiducialNameRule = fiducialNameRule;
        this.pasteDnp = pasteDnp;
    }

    /** Notes of one code and text, merged over the footprints that share them. */
    private static final class Merged {
        final String code;
        final String detail;
        final List<String> refs = new ArrayList<>();

        Merged(String code, String detail) {
            this.code = code;
            this.detail = detail;
        }
    }

    public ImportProposal propose(KicadPcb pcb, BoardFrame frame, Deadline deadline) throws SExprException {
        ImportProposal out = new ImportProposal();
        out.widthMm = frame.widthMm();
        out.heightMm = frame.heightMm();
        out.minX = KGeom.round6(frame.minX);
        out.maxY = KGeom.round6(frame.maxY);
        out.outline = frame.outline;
        out.origin = frame.origin;
        out.pasteDnp = pasteDnp;
        for (ImportWarning w : pcb.warnings) {
            out.warn(w);
        }
        out.warningsDropped += pcb.warningsDropped;
        for (ImportWarning w : frame.warnings) {
            out.warn(w);
        }
        Map<String, Merged> notes = new LinkedHashMap<>();
        Map<String, List<Integer>> variants = new LinkedHashMap<>();
        Set<String> usedIds = new HashSet<>();
        Map<String, String> padIds = new HashMap<>(); // pad id -> placement id
        List<String> dnpSkipped = new ArrayList<>();
        List<String> offBoard = new ArrayList<>();
        out.counts.footprints = pcb.footprints.size();
        for (int i = 0; i < pcb.footprints.size(); i++) {
            deadline.check();
            Footprint fp = pcb.footprints.get(i);
            FootprintP f = new FootprintP();
            f.index = i;
            f.ref = KText.clean(fp.ref, KText.ID_MAX);
            out.footprints.add(f);
            if (!fp.top) {
                f.kind = FootprintP.BOTTOM;
                out.skipped.get(ImportProposal.SKIP_BOTTOM).add(f.ref);
                out.counts.bottom++;
                continue;
            }
            boolean fiducial = isFiducial(fp);
            PasteRules.Result r = fiducial ? PasteRules.copper(pcb, fp, deadline) : PasteRules.paste(pcb, fp, deadline);
            boolean dnp = fp.hasAttr("dnp") && !fiducial;
            if (r.apertures.isEmpty()) {
                if (fiducial) {
                    out.warn(ImportWarning.of("FIDUCIAL_NO_COPPER", Collections.singletonList(f.ref), f.ref
                            + " has no copper pad, so it cannot be found by the camera: it is drawn only."));
                    artOnly(out, f, "fiducial without copper");
                }
                else {
                    for (PasteRules.Note n : r.notes) { // why there is no paste
                        if (n.code.equals("THT_PASTE_SKIPPED") || n.code.equals("APERTURE_GONE")
                                || n.code.equals("PASTE_ZONE_IGNORED")) {
                            note(notes, n.code, n.detail, f.ref);
                        }
                    }
                    artOnly(out, f, fp.hasAttr("board_only") ? "board only"
                            : fp.hasAttr("through_hole") ? "through-hole" : "no paste pads");
                }
                continue;
            }
            if (!fiducial && !onBoard(frame, fp, r.apertures)) {
                // a spare parked next to the board, or a part past its edge: its paste would land off the board
                artOnly(out, f, "outside the board");
                offBoard.add(f.ref);
                continue;
            }
            if (dnp && !pasteDnp) {
                f.kind = FootprintP.DNP;
                f.why = "do not populate";
                out.skipped.get(ImportProposal.SKIP_DNP).add(f.ref);
                out.counts.dnp++;
                dnpSkipped.add(f.ref);
                continue;
            }
            PlacementP p = new PlacementP();
            p.footprintIndex = i;
            p.ref = f.ref;
            p.value = KText.clean(fp.value, KText.ID_MAX);
            p.type = fiducial ? "Fiducial" : "Placement";
            p.enabled = true;
            p.id = placementId(out, f.ref, usedIds);
            if (!fiducial) {
                p.id = uniquePadIds(out, padIds, usedIds, p.id, r.apertures, deadline);
            }
            f.ref = p.id;
            f.placement = out.placements.size();
            if (fiducial) {
                f.kind = FootprintP.FIDUCIAL;
                out.counts.fiducials++;
            }
            else if (dnp) {
                f.kind = FootprintP.DNP;
                p.enabled = false;
                p.why = "dnp";
                out.skipped.get(ImportProposal.SKIP_DNP).add(p.id);
                out.counts.dnp++;
                out.warn(ImportWarning.of("DNP_STILL_PASTED", Collections.singletonList(p.id), p.id + " is marked Do "
                        + "not populate. It will not be placed, but its " + r.apertures.size() + " pads still get "
                        + "paste (the engine pastes disabled parts)."));
            }
            else if (fp.hasAttr("exclude_from_pos_files") || fp.hasAttr("board_only")) {
                f.kind = FootprintP.PASTE_ONLY;
                p.enabled = false;
                p.why = "pasteOnly";
                out.skipped.get(ImportProposal.SKIP_PASTE_ONLY).add(p.id);
                out.counts.pasteOnly++;
            }
            else {
                f.kind = FootprintP.PLACEMENT;
                if (DNP_VALUE.matcher(p.value.trim()).matches()) {
                    note(notes, "VALUE_SAYS_DNP", "'s value says " + p.value + " but the part is not marked Do not "
                            + "populate in KiCad: it is imported as a normal part and gets paste.", p.id);
                }
            }
            idCleaned(notes, fp, p);
            for (PasteRules.Note n : r.notes) {
                note(notes, n.code, n.detail, p.id);
            }
            double[] b = frame.toBoard(fp.x, fp.y);
            p.x = KGeom.round6(b[0]);
            p.y = KGeom.round6(b[1]);
            p.rotation = KGeom.round6(fp.angle);
            p.pads = r.apertures.size();
            p.packageIndex = packageFor(out, variants, fp, fiducial, r.apertures, p.id);
            if (!fiducial) {
                out.counts.placements++;
                out.counts.pastePads += r.apertures.size();
            }
            out.placements.add(p);
        }
        for (Map.Entry<String, List<Integer>> e : variants.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            for (int k : e.getValue()) {
                refs.add(out.packages.get(k).refs.get(0));
            }
            int n = refs.size();
            out.warn(ImportWarning.of("PACKAGE_VARIANT", refs, n == 2
                    ? "Two different footprints are both called " + e.getKey() + " (" + refs.get(0) + " and "
                            + refs.get(1) + " differ): the second is saved as its own package."
                    : n + " different footprints are called " + e.getKey() + " (" + ImportWarning.names(refs)
                            + " differ): each is saved as its own package."));
        }
        for (Merged m : notes.values()) {
            out.warn(ImportWarning.of(m.code, m.refs, ImportWarning.names(m.refs) + m.detail));
        }
        boardPaste(pcb, out);
        if (out.counts.placements == 0) {
            out.warn(ImportWarning.of("NO_PLACEMENTS", null,
                    "No top-side part has paste pads: there is nothing to dispense on this board."));
        }
        if (out.counts.fiducials < 2) {
            out.warn(ImportWarning.of("FIDUCIAL_COUNT", null, (out.counts.fiducials == 0 ? "No fiducial"
                    : "Only 1 fiducial") + " on the top side: Register board needs 2 (3 are better). Without them, "
                    + "place the board by hand and probe it by hand."));
        }
        if (!offBoard.isEmpty()) {
            boolean one = offBoard.size() == 1;
            out.warn(ImportWarning.of("OFF_BOARD", offBoard, ImportWarning.names(offBoard) + (one ? " is" : " are")
                    + " not inside the board outline: " + (one ? "it gets no paste (its" : "they get no paste (their")
                    + " paste would land off the board). Move " + (one ? "it" : "them") + " onto the board in KiCad if "
                    + (one ? "it is" : "they are") + " part of this board."));
        }
        if (!dnpSkipped.isEmpty()) {
            boolean one = dnpSkipped.size() == 1;
            out.warn(ImportWarning.of("DNP_SKIPPED", dnpSkipped, ImportWarning.names(dnpSkipped)
                    + (one ? " is" : " are") + " marked Do not populate: not placed and no paste. Tick \"Paste "
                    + "do-not-place parts too\" to paste " + (one ? "it" : "them") + " as a disabled part."));
        }
        List<String> bottom = out.skipped.get(ImportProposal.SKIP_BOTTOM);
        if (!bottom.isEmpty()) {
            out.warn(ImportWarning.of("BOTTOM_SKIPPED", bottom, bottom.size() + (bottom.size() == 1
                    ? " bottom-side part gets" : " bottom-side parts get")
                    + " no paste (only the top is dispensed): " + ImportWarning.names(bottom) + "."));
        }
        out.sortWarnings();
        return out;
    }

    /** Library "Fiducial", reference FID…, or (extension) a footprint name starting with "Fiducial". */
    boolean isFiducial(Footprint fp) {
        return "fiducial".equalsIgnoreCase(fp.lib) || FID_REF.matcher(fp.ref).find()
                || fiducialNameRule && FID_NAME.matcher(fp.name).find();
    }

    /**
     * Every paste opening of the footprint lies inside the board's box (§2.6: the outline's extents), to 0.001 mm;
     * an opening is a rectangle at the footprint's angle plus its own (§2.8).
     */
    private static boolean onBoard(BoardFrame frame, Footprint fp, List<PasteRules.Aperture> apertures) {
        double w = frame.widthMm();
        double h = frame.heightMm();
        double[] at = frame.toBoard(fp.x, fp.y);
        double t = Math.toRadians(fp.angle);
        for (PasteRules.Aperture a : apertures) {
            double cx = at[0] + a.x * Math.cos(t) - a.y * Math.sin(t);
            double cy = at[1] + a.x * Math.sin(t) + a.y * Math.cos(t);
            double u = Math.toRadians(fp.angle + a.rotation);
            double hx = (Math.abs(a.w * Math.cos(u)) + Math.abs(a.h * Math.sin(u))) / 2;
            double hy = (Math.abs(a.w * Math.sin(u)) + Math.abs(a.h * Math.cos(u))) / 2;
            if (cx - hx < -EDGE_TOL_MM || cx + hx > w + EDGE_TOL_MM || cy - hy < -EDGE_TOL_MM
                    || cy + hy > h + EDGE_TOL_MM) {
                return false;
            }
        }
        return true;
    }

    private static void artOnly(ImportProposal out, FootprintP f, String why) {
        f.kind = FootprintP.ART;
        f.why = why;
        out.skipped.get(ImportProposal.SKIP_ART_ONLY).add(f.ref);
        out.counts.artOnly++;
    }

    /** The cleaned reference; empty → P~1, P~2 …; a reference already used → ~2, ~3 … (DUPLICATE_REF). */
    private static String placementId(ImportProposal out, String cleanRef, Set<String> used) {
        if (cleanRef.isEmpty()) {
            int k = 1;
            while (used.contains("P~" + k)) {
                k++;
            }
            used.add("P~" + k);
            return "P~" + k;
        }
        if (used.add(cleanRef)) {
            return cleanRef;
        }
        int k = 2;
        while (used.contains(cleanRef + "~" + k)) {
            k++;
        }
        String id = cleanRef + "~" + k;
        used.add(id);
        out.warn(ImportWarning.of("DUPLICATE_REF", Collections.singletonList(id),
                "Two parts are named " + cleanRef + ": the second is saved as " + id + "."));
        return id;
    }

    /** ID_CLEANED for the reference, value and footprint name that cleaning changed, with what was saved. */
    private static void idCleaned(Map<String, Merged> notes, Footprint fp, PlacementP p) {
        String[][] fields = {{fp.ref, "reference", p.id}, {fp.value, "value", p.value},
                {fp.name, "footprint name", KText.cleanId(fp.name, "FP")}};
        for (String[] f : fields) {
            String raw = f[0] == null ? "" : f[0];
            if (!raw.isEmpty() && !raw.equals(KText.clean(raw, KText.ID_MAX))) {
                note(notes, "ID_CLEANED", "'s " + f[1] + " had characters OpenPnP cannot store ("
                        + KText.whatChanged(raw, KText.ID_MAX) + "): saved as \"" + f[2] + "\".", p.id);
            }
        }
    }

    private static void note(Map<String, Merged> notes, String code, String detail, String ref) {
        notes.computeIfAbsent(code + '\u0000' + detail, k -> new Merged(code, detail)).refs.add(ref);
    }

    /** The package variant of a footprint: same cleaned name and same pads → same package. */
    private static int packageFor(ImportProposal out, Map<String, List<Integer>> variants, Footprint fp,
            boolean fiducial, List<PasteRules.Aperture> apertures, String ref) {
        String baseId = KText.cleanId(fp.name, "FP");
        String sig = signature(apertures);
        List<Integer> list = variants.computeIfAbsent(baseId, k -> new ArrayList<>());
        for (int k : list) {
            PackageP pk = out.packages.get(k);
            if (pk.signature.equals(sig) && pk.fiducial == fiducial) {
                pk.refs.add(ref);
                return k;
            }
        }
        PackageP pk = new PackageP();
        pk.baseId = baseId;
        pk.variant = list.size() + 1;
        pk.lib = KText.clean(fp.lib, KText.ID_MAX);
        pk.kicadName = KText.clean(fp.name, KText.ID_MAX);
        pk.fiducial = fiducial;
        pk.signature = sig;
        for (PasteRules.Aperture a : apertures) {
            PadP pad = new PadP();
            pad.name = a.name;
            pad.x = a.x;
            pad.y = a.y;
            pad.w = a.w;
            pad.h = a.h;
            pad.rotation = a.rotation;
            pad.roundness = a.roundness;
            pad.approx = a.approx;
            pk.pads.add(pad);
        }
        double[] body = bodyBox(fp, "F.Fab");
        if (KGeom.isEmpty(body)) {
            body = bodyBox(fp, "F.CrtYd");
        }
        if (!KGeom.isEmpty(body)) {
            pk.bodyW = KGeom.round6(body[2] - body[0]);
            pk.bodyH = KGeom.round6(body[3] - body[1]);
        }
        pk.refs.add(ref);
        out.packages.add(pk);
        list.add(out.packages.size() - 1);
        return out.packages.size() - 1;
    }

    /** The pads sorted by name, each (name, x, y, w, h, rotation, roundness) to 0.001 mm, 0.001° and 0.1 %. */
    static String signature(List<PasteRules.Aperture> apertures) {
        List<PasteRules.Aperture> l = new ArrayList<>(apertures);
        l.sort(Comparator.comparing(a -> a.name));
        StringBuilder sb = new StringBuilder();
        for (PasteRules.Aperture a : l) {
            sb.append(a.name).append(':').append(f3(a.x)).append(',').append(f3(a.y)).append(',').append(f3(a.w))
                    .append(',').append(f3(a.h)).append(',').append(deg3(a.rotation)).append(',')
                    .append(String.format(Locale.ROOT, "%.1f", a.roundness)).append(';');
        }
        return sb.toString();
    }

    private static String f3(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        return "-0.000".equals(s) ? "0.000" : s;
    }

    /**
     * A pad rotation to 0.001° in (−180, 180]: −180.000 reads 180.000. A relative angle of 180 + ε (256.1 − 76.1 in
     * floating point) normalises to −180 + ε, which rounds to −180.000, and two copies of one footprint were split.
     */
    private static String deg3(double v) {
        String s = f3(v);
        return "-180.000".equals(s) ? "180.000" : s;
    }

    private static double[] bodyBox(Footprint fp, String layer) {
        double[] box = KGeom.emptyBox();
        for (Graphic g : fp.graphics) {
            if (layer.equals(g.layer)) {
                KGeom.extend(box, BoardFrame.graphicBox(g.kind, g.pts));
            }
        }
        return box;
    }

    /**
     * Pad ids (placement.pad) key done tracking, so they must be unique over the board: on a clash this placement's
     * id gets the next free ~n (PAD_ID_CLASH). Fiducials have no dispensed pads.
     */
    private static String uniquePadIds(ImportProposal out, Map<String, String> padIds, Set<String> usedIds,
            String id, List<PasteRules.Aperture> apertures, Deadline deadline) throws SExprException {
        String result = id;
        for (int k = 2; ; k++) {
            deadline.check(); // each try scans every opening of the part
            String clash = null;
            for (PasteRules.Aperture a : apertures) {
                if (padIds.containsKey(result + "." + a.name)) {
                    clash = result + "." + a.name;
                    break;
                }
            }
            if (clash == null) {
                break;
            }
            String next = id + "~" + k;
            while (usedIds.contains(next)) {
                next = id + "~" + (++k);
            }
            usedIds.add(next);
            out.warn(ImportWarning.of("PAD_ID_CLASH", Collections.singletonList(next), padIds.get(clash) + " and "
                    + result + " would share the pad id " + clash + ": " + result + " is saved as " + next + "."));
            result = next;
        }
        for (PasteRules.Aperture a : apertures) {
            padIds.put(result + "." + a.name, result);
        }
        return result;
    }

    /** Paste drawn on the board outside any part is drawn only (BOARD_PASTE_GRAPHIC). */
    private static void boardPaste(KicadPcb pcb, ImportProposal out) {
        int n = 0;
        for (Graphic g : pcb.graphics) {
            if ("F.Paste".equals(g.layer)) {
                n++;
            }
        }
        for (KicadPcb.Zone z : pcb.zones) {
            if (!z.keepout && (z.layers.contains("F.Paste") || z.layers.contains("*.Paste"))) {
                n++;
            }
        }
        if (n > 0) {
            out.warn(ImportWarning.of("BOARD_PASTE_GRAPHIC", null, n + (n == 1 ? " paste shape is" : " paste shapes are")
                    + " drawn on the board outside any part: drawn only, no paste."));
        }
    }
}
