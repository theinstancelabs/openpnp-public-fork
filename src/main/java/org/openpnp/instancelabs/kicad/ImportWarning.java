package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One warning or note of an import (design §2.10). Every code belongs to one group, and the preview lists them in
 * group order: 1 blocks dispensing, 2 registration, 3 check the preview, 4 notes (collapsed).
 */
public final class ImportWarning {
    public static final String WARN = "warn";
    public static final String INFO = "info";

    public String code;
    public String level;
    public int group;
    public List<String> refs = new ArrayList<>();
    public String text;

    private static final Map<String, Integer> GROUPS = new HashMap<>();

    static {
        for (String c : new String[] {"NO_PAD_TYPE", "NEEDS_PROFILE", "NO_PLACEMENTS", "APERTURE_GONE"}) {
            GROUPS.put(c, 1);
        }
        for (String c : new String[] {"FIDUCIAL_COUNT", "FIDUCIAL_NO_COPPER"}) {
            GROUPS.put(c, 2);
        }
        for (String c : new String[] {"NO_EDGE_CUTS", "OUTLINE_OPEN", "KICAD5", "KICAD_NEWER", "DNP_STILL_PASTED",
                "VALUE_SAYS_DNP", "THT_PASTE_SKIPPED", "QFN_WINDOWS", "PAD_SHAPE_APPROX", "PASTE_POLY_APPROX",
                "PACKAGE_MAPPING_LOST", "PACKAGE_VARIANT", "DUPLICATE_REF", "PAD_ID_CLASH", "ID_CLEANED",
                "BAD_NUMBER", "BOARD_PASTE_GRAPHIC", "PASTE_ZONE_IGNORED", "ART_REDUCED", "OFF_BOARD"}) {
            GROUPS.put(c, 3);
        }
        for (String c : new String[] {"BOTTOM_SKIPPED", "DNP_SKIPPED", "PACKAGE_SUFFIXED", "PART_SUFFIXED",
                "PAD_NAME_EMPTY", "PAD_NAME_DUPLICATE", "PASTE_POLY", "CHAMFER_IGNORED", "PASTE_ZERO_OVERRIDE",
                "PADSTACK_IGNORED", "ZONES_UNFILLED", "ART_SIMPLIFIED", "WARNINGS_TRUNCATED"}) {
            GROUPS.put(c, 4);
        }
    }

    /** The group of a code (an unknown code is group 3, "check the preview"). */
    public static int groupOf(String code) {
        Integer g = GROUPS.get(code);
        return g == null ? 3 : g;
    }

    /** A warning with the code's own group; group 4 is a note (info), every other group a warning. */
    public static ImportWarning of(String code, List<String> refs, String text) {
        int g = groupOf(code);
        return of(code, g, g == 4 ? INFO : WARN, refs, text);
    }

    public static ImportWarning of(String code, int group, String level, List<String> refs, String text) {
        ImportWarning w = new ImportWarning();
        w.code = code;
        w.group = group;
        w.level = level;
        w.refs = refs == null ? new ArrayList<>() : new ArrayList<>(refs);
        w.text = text;
        return w;
    }

    /** "R1, R2, R3" (at most 10 names, then "… and 30 more"). */
    public static String names(List<String> refs) {
        if (refs == null || refs.isEmpty()) {
            return "";
        }
        int show = Math.min(10, refs.size());
        StringBuilder sb = new StringBuilder(String.join(", ", refs.subList(0, show)));
        if (refs.size() > show) {
            sb.append(" and ").append(refs.size() - show).append(" more");
        }
        return sb.toString();
    }

    /**
     * Adds w to a list kept to max entries. Once full, w replaces the last entry of the least important group (the
     * highest group number) when that group is less important than w's, so a flood of notes cannot push out what
     * blocks dispensing. Returns false when something was dropped (w or the entry it replaced).
     */
    public static boolean addCapped(List<ImportWarning> list, ImportWarning w, int max) {
        if (list.size() < max) {
            list.add(w);
            return true;
        }
        int drop = -1;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).group > w.group && (drop < 0 || list.get(i).group > list.get(drop).group)) {
                drop = i;
            }
        }
        if (drop >= 0) {
            list.remove(drop);
            list.add(w);
        }
        return false;
    }

    /** Stable sort by group: the order the preview lists them in. */
    public static void sort(List<ImportWarning> warnings) {
        Collections.sort(warnings, (a, b) -> Integer.compare(a.group, b.group));
    }

    @Override
    public String toString() {
        return code + "(" + level + ", " + group + "): " + text;
    }
}
