package org.openpnp.instancelabs.padprofile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every sentence of the profile check (§3.4) and its number formats: Locale-free, mm and mg to 2
 * decimals, % whole, half up on the decimal value (1.475 mm -> 1.48), plurals as IL.plural.
 */
public final class Texts {
    private static final String NEEDS_OTHER = " A finer tip, a paste line or a stencil would be needed; this version does "
            + "not do those.";

    private Texts() {
    }

    /** x to {@code decimals} places, half up on its shortest decimal form; "-0.00" becomes "0.00". */
    public static String num(double x, int decimals) {
        String s = BigDecimal.valueOf(x).setScale(decimals, RoundingMode.HALF_UP).toPlainString();
        return s.matches("-0(\\.0*)?") ? s.substring(1) : s;
    }

    public static String mm(double x) {
        return num(x, 2);
    }

    public static String mg(double x) {
        return num(x, 2);
    }

    /** A fraction as a whole percent of its size: 0.162 -> "16". */
    public static String pct(double fraction) {
        return num(Math.abs(fraction) * 100, 0);
    }

    /** A fraction as a signed whole percent: -0.33 -> "-33", 0.05 -> "+5", 0 -> "0". */
    public static String signedPct(double fraction) {
        String p = pct(fraction);
        return "0".equals(p) ? p : (fraction < 0 ? "-" : "+") + p;
    }

    /** "8 % less than the 2.29 mg target", or "on the 2.29 mg target" when the error rounds to 0 %. */
    public static String vsTarget(double fraction, double targetMg) {
        String p = pct(fraction);
        return "0".equals(p) ? "on the " + mg(targetMg) + " mg target"
                : p + " % " + (fraction < 0 ? "less" : "more") + " than the " + mg(targetMg) + " mg target";
    }

    /** Whole percent rounded half up (the errorPct field). */
    public static int wholePct(double fraction) {
        return new BigDecimal(Double.toString(fraction * 100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** "1 pad", "3 pads". */
    public static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    /** Profile's status words (W/profile.js STATUS_OPTION). */
    public static String statusWord(String status) {
        switch (TypeInfo.rank(status)) {
            case 0:
                return "not started";
            case 1:
                return "provisional";
            case 2:
                return "characterized";
            case 3:
                return "validated on a board";
            default:
                return String.valueOf(status);
        }
    }

    /** "SOT-23 · 1.48 × 0.60 mm". */
    public static String label(String packageId, double longMm, double shortMm) {
        return GroupKey.basePackage(packageId) + " · " + mm(longMm) + " × " + mm(shortMm) + " mm";
    }

    /** "U3 pad 2" for a pad id's part and pad. */
    public static String padWords(PadInstance p) {
        return p.ref + " pad " + p.padName;
    }

    // ---- tip ------------------------------------------------------------------------------------

    /**
     * The pitch texts: "gap > 0" and "gap <= 0" of §3.4, split where the overlap shows as at least
     * 0.01 mm (an overlap of "0.00 mm" reads as the gap text with 0.00).
     */
    public static String tipPitch(String tipName, double pitch, double dMin, int minSteps, double gap) {
        if (gap <= -0.005) {
            return "Not dispensable with the " + tipName + ": a " + mm(dMin) + " mm dot, the smallest this tip makes "
                    + "reliably, is wider than the " + mm(pitch) + " mm pad pitch, so neighbouring dots would overlap by "
                    + mm(-gap) + " mm." + NEEDS_OTHER;
        }
        return "Not dispensable with the " + tipName + ": these pads are " + mm(pitch) + " mm apart, and the smallest "
                + "dot this tip makes reliably (" + mm(dMin) + " mm, " + DoseModel.turnWithArticle(minSteps)
                + ") would leave only " + mm(Math.max(0, gap)) + " mm to the next pad's dot (at least "
                + mm(Limits.DOT_GAP_MARGINAL_MM) + " mm needed)." + NEEDS_OTHER;
    }

    public static String tipCopper(String tipName, double dMin, double into) {
        return "Not dispensable with the " + tipName + ": the smallest dot this tip makes reliably (" + mm(dMin)
                + " mm) would reach " + mm(into) + " mm onto the next pad." + NEEDS_OTHER;
    }

    /**
     * No plan fits (§4.3 step 9). The closest plan's violations, each of overhang beyond 0.25 mm,
     * dot gap below 0.20 mm and copper gap below 0.10 mm, are named (null: not violated).
     */
    public static String tipNoPlan(String tipName, double w, double h, int n, int steps, Double overhang,
            Double dotGap, Double copperGap, double massMg, double targetMg) {
        StringBuilder b = new StringBuilder("Not dispensable with the " + tipName + ": on these " + mm(w) + " × "
                + mm(h) + " mm pads the closest plan (" + n + " " + DoseModel.turnName(steps) + " "
                + (n == 1 ? "dot" : "dots") + " per pad)");
        String sep = " ";
        if (overhang != null) {
            b.append(sep).append("reaches ").append(mm(overhang)).append(" mm past the pad edge (at most ")
                    .append(mm(Limits.OVERHANG_MARGINAL_MM)).append(" mm)");
            sep = " and ";
        }
        if (dotGap != null) {
            b.append(sep).append("comes within ").append(mm(dotGap)).append(" mm of the next pad's dot (at least ")
                    .append(mm(Limits.DOT_GAP_MARGINAL_MM)).append(" mm)");
            sep = " and ";
        }
        if (copperGap != null) {
            b.append(sep).append("comes within ").append(mm(copperGap)).append(" mm of the next pad (at least ")
                    .append(mm(Limits.COPPER_GAP_MARGINAL_MM)).append(" mm)");
            sep = " and ";
        }
        if (" ".equals(sep)) {
            double e = massMg / targetMg - 1;
            b.append(" gives ").append(mg(massMg)).append(" mg per pad, ").append(pct(e)).append(" % ")
                    .append(e < 0 ? "less" : "more").append(" than the ").append(mg(targetMg)).append(" mg these pads need");
        }
        return b.append('.').toString();
    }

    /**
     * Appended to a group of a package whose other pads are not dispensable, when its package is
     * opted in; {@code otherPads} is "0.80 mm apart" (§3.4's finePitch) or "0.61 mm wide" (no plan).
     */
    public static String partialSentence(String packageId, String otherPads) {
        return " The other pads of " + GroupKey.basePackage(packageId) + " (" + otherPads + ") cannot be "
                + "dispensed with this tip: paste these only if you solder those pins by hand.";
    }

    /** No dose of R1 is allowed for the tip at all. */
    public static String tipNoDose(String tipName, int minSteps) {
        return "Not dispensable with the " + tipName + ": its smallest dose (" + minSteps + " steps) is more than one "
                + "screw turn, and no quarter, half or one-turn dot is allowed with it.";
    }

    /** A part with an undispensable pad is treated whole (main-thread decision 4). */
    public static String part(String packageId, String tipName, String otherPads) {
        String pkg = GroupKey.basePackage(packageId);
        return "The other pads of " + pkg + " (" + otherPads + ") cannot be dispensed with the " + tipName
                + ", so none of this part's pads are pasted: a part whose pins are soldered by hand gets no paste on its "
                + "other pads either. To paste these pads anyway, choose “Paste the dispensable pads of this part” "
                + "for " + pkg + ".";
    }

    /** Decisions 4 and 14: a part held whole because another of its pads is too close to a neighbour. */
    public static String nearPart(String packageId) {
        return "Another pad of this part is too close to a neighbour and gets no paste, so none of this part's pads "
                + "are pasted: a part whose pins are soldered by hand gets no paste on its other pads either. To paste "
                + "these pads anyway, choose “Paste the dispensable pads of this part” for "
                + GroupKey.basePackage(packageId) + ".";
    }

    /**
     * Decision 14: {@code k} of a group's {@code of} pads whose own neighbour breaks the plan the others
     * keep; {@code gap} is to the neighbour's dot ({@code byDot}) or copper, null when there is no plan.
     */
    public static String near(int k, int of, String neighbour, Double gap, boolean byDot) {
        String limit = byDot ? " (at least " + mm(Limits.DOT_GAP_MARGINAL_MM) + " mm)"
                : " (at least " + mm(Limits.COPPER_GAP_MARGINAL_MM) + " mm)";
        String what = gap == null ? "their dots would come too close to " + neighbour
                : gap < -0.005 && byDot ? "their dots would overlap the dot of " + neighbour + " by " + mm(-gap) + " mm"
                        : "their dots would leave " + mm(Math.max(0, gap)) + " mm to " + (byDot ? "the dot of "
                                + neighbour : neighbour + "'s copper") + limit;
        return k + " of " + plural(of, "pad", "pads") + (k == 1 ? " is" : " are") + " too close to a neighbour: "
                + what + ", so " + (k == 1 ? "it gets" : "they get") + " no paste. The other " + (of - k)
                + " keep the plan.";
    }

    // ---- what Profile can fix -----------------------------------------------------------------

    public static String noPadSize(String packageId, String typeName) {
        return GroupKey.basePackage(packageId) + " has no pads in OpenPnP and its pad type "
                + (typeName == null ? "(none)" : typeName) + " has no pad layout: add the footprint's pads in OpenPnP, "
                + "or upload the KiCad board.";
    }

    public static String noType(String packageId, double w, double h, int pads, int parts) {
        return "No pad type for " + GroupKey.basePackage(packageId) + " (" + mm(w) + " × " + mm(h) + " mm openings, "
                + plural(pads, "pad", "pads") + " on " + plural(parts, "part", "parts") + "): these pads get no paste "
                + "until a pad type is chosen.";
    }

    /**
     * Tip setups (§22.2): the below-gate text of a type for the mounted setup, with the other setups' statuses:
     * "{type} is not calibrated for the {S} yet (status: {status}; {other status} for the {other setup}). Boards need
     * pad types that are at least {gate}: calibrate it for this tip automatically or by hand."
     */
    public static String belowGate(String typeName, String status, String gate, String setupName,
            List<TypeInfo.Other> others) {
        StringBuilder b = new StringBuilder(typeName).append(" is not calibrated for the ").append(setupName)
                .append(" yet (status: ").append(statusWord(status));
        if (others != null) {
            for (TypeInfo.Other o : others) {
                b.append("; ").append(statusWord(o.status)).append(" for the ").append(o.setupName);
            }
        }
        return b.append("). Boards need pad types that are at least ").append(statusWord(gate))
                .append(": calibrate it for this tip automatically or by hand.").toString();
    }

    /**
     * The "calibrated for" label of a calibration record (§22.5): "{type} · {setup} · {dispenser} · {psi} psi ·
     * {paste}{ lot {lot}}", the type as Profile names it (family word and name; a name holding "·" in brackets);
     * over 160 characters the paste, then the type, is cut with "…". E.g. "Chip 0603 · 22G bent taper · Vieweg
     * DV-5005 · 18 psi · Loctite GC50 SAC305 T5".
     */
    public static String calLabel(String family, String typeName, String setupName, String dispenser,
            double pressurePsi, String pasteName, String pasteLot) {
        String type = typeWords(family, typeName);
        String middle = " · " + setupName + " · " + dispenser + " · "
                + BigDecimal.valueOf(pressurePsi).stripTrailingZeros().toPlainString() + " psi · ";
        String paste = (pasteName == null ? "" : pasteName)
                + (pasteLot == null || pasteLot.trim().isEmpty() ? "" : " lot " + pasteLot.trim());
        String label = type + middle + paste;
        if (label.length() <= MAX_LABEL) {
            return label;
        }
        // the paste first, then the type, cut with "…"
        int room = MAX_LABEL - type.length() - middle.length();
        if (room >= 2) {
            return type + middle + cut(paste, room);
        }
        String noPaste = middle + cut(paste, 2);
        return cut(type, MAX_LABEL - noPaste.length()) + noPaste;
    }

    /** The longest "calibrated for" label (§22.5). */
    public static final int MAX_LABEL = 160;

    /**
     * A pad type as Profile names it (W/padprofile.js typeLabel): the family's word (its note in brackets dropped)
     * before the name when it is one word and not Custom, "Chip 0603"; a name holding "·" keeps its first part and
     * the rest in brackets, "SOT-23 (1.48 × 0.60 mm)".
     */
    public static String typeWords(String family, String typeName) {
        String name = typeName == null ? "" : typeName.trim();
        if (name.contains("·")) {
            String[] parts = name.split("\\s*·\\s*");
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    rest.append(rest.length() == 0 ? "" : ", ").append(parts[i]);
                }
            }
            name = parts[0] + (rest.length() == 0 ? "" : " (" + rest + ")");
        }
        String fam = family == null ? "" : family.replaceAll("\\s*\\([^)]*\\)\\s*", " ").replaceAll("\\s+", " ")
                .trim();
        return fam.matches("\\S+") && !"custom".equalsIgnoreCase(fam) && !(typeName != null && typeName.contains("·"))
                ? fam + " " + name : name;
    }

    /** s cut to at most n characters, the last of them "…". */
    private static String cut(String s, int n) {
        return s.length() <= n ? s : n <= 1 ? "…" : s.substring(0, n - 1).trim() + "…";
    }

    /**
     * Tip setups (§22.2): a type whose dose the mounted setup's dot table does not know at the current recipe and
     * paste (the planner's DOSE_UNKNOWN).
     */
    public static String doseUnknown(String typeName, int steps, String setupName) {
        return typeName + " uses " + DoseModel.turnName(steps) + " dots, which the " + setupName + " never measured at "
                + "this recipe and paste: measure its dots (Setup › Dispenser & tip).";
    }

    /** §22.2: the measure-tip suggestion of an unmeasured setup. */
    public static String measureTip(String setupName) {
        return "Measure the " + setupName + "'s dots first (Setup › Dispenser & tip › Measure its dots, ≈ 8 min and two "
                + "coupons to weigh): suggestions need its dot sizes and paste per turn.";
    }

    /** A type calibrated with another tip blocks like a type below the gate (main-thread decision 5). */
    public static String belowGateTip(String typeName, String oldTip, String tipName) {
        return typeName + " was calibrated with another tip (" + oldTip + "); the mounted tip is the " + tipName
                + ". Mass per turn and dot size change with the tip: calibrate it again for this tip.";
    }

    /** Dots measured or modelled closer than 0.20 mm to a neighbour's dot (main-thread decision 11). */
    public static String bridge(String typeName, String neighbour, double gap) {
        return typeName + "'s dots may bridge to " + neighbour + ": they would leave " + mm(gap) + " mm to its dot (at "
                + "least " + mm(Limits.DOT_GAP_MARGINAL_MM) + " mm). A pad type for this size avoids it.";
    }

    public static String bridgeShort(String neighbour) {
        return "dots may bridge to " + neighbour;
    }

    // ---- notes ----------------------------------------------------------------------------------

    public static String outsideArea(String typeName, double tw, double th, double tArea, double w, double h,
            double area, double plannedMg, double aboutMg) {
        double ratio = area / tArea - 1;
        return typeName + " was calibrated for " + mm(tw) + " × " + mm(th) + " mm pads (" + mm(tArea) + " mm²); these are "
                + mm(w) + " × " + mm(h) + " mm (" + mm(area) + " mm², " + pct(ratio) + " % "
                + (ratio >= 0 ? "larger" : "smaller") + "), so each gets " + mg(plannedMg) + " mg instead of about "
                + mg(aboutMg) + " mg.";
    }

    public static String outsideOverhang(String typeName, double overhang) {
        return typeName + "'s dots on these pads would reach " + mm(overhang) + " mm past the pad edge (at most "
                + mm(Limits.OVERHANG_MARGINAL_MM) + " mm): a pad type for this size avoids it.";
    }

    public static String outsideCopper(String typeName, double gap) {
        return typeName + "'s dots on these pads would come within " + mm(gap) + " mm of the next pad (at least "
                + mm(Limits.COPPER_GAP_MARGINAL_MM) + " mm): a pad type for this size avoids it.";
    }

    public static String outsideRecipe(String typeName, String diff) {
        return typeName + " was calibrated with another recipe (" + diff + "); calibrate it again, or set the profile back.";
    }

    /** §6.5 PLAN refusal: a dose the tip cannot do. */
    public static String interlockDose(String key, int version, int steps, String tipName, int minSteps) {
        return "pad type " + key + " v" + version + " has " + DoseModel.turnName(steps) + " dots; the " + tipName
                + " takes quarter, half or one-turn dots (" + DoseModel.turnWithArticle(minSteps)
                + " at least): edit it and plan again";
    }

    /** §6.5 PLAN refusal: R6. */
    public static String interlockR6(String key, int version, int padSteps) {
        return "pad type " + key + " v" + version + " gives each pad " + padSteps + " steps ("
                + DoseModel.turnWithArticle(padSteps) + "); every pad needs at least half a screw turn in a row, or its "
                + "paste varies more with the screw angle than the validated 0603 pads: use more dots or a larger turn";
    }

    /**
     * The one recipe formatter (decision 18; D/DispenseProfile.fingerprintDiff is this): what differs
     * between two fingerprints ("psi=18.000 suck=10.000 …") in words, e.g. {@code "dwell 250 -> 300,
     * gap 0.1 -> 0.12"}, numbers without trailing zeros, keys only one side has with "none"; empty when
     * equal.
     */
    public static String recipeDiff(String was, String now) {
        Map<String, String> a = fields(was);
        Map<String, String> b = fields(now);
        Set<String> keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        StringBuilder s = new StringBuilder();
        for (String k : keys) {
            String x = a.getOrDefault(k, "none");
            String y = b.getOrDefault(k, "none");
            if (!x.equals(y)) {
                s.append(s.length() == 0 ? "" : ", ").append(k).append(' ').append(x).append(" -> ").append(y);
            }
        }
        return s.toString();
    }

    private static Map<String, String> fields(String fp) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : (fp == null ? "" : fp).trim().split("\\s+")) {
            int i = t.indexOf('=');
            if (i > 0) {
                String v = t.substring(i + 1);
                try {
                    v = BigDecimal.valueOf(Double.parseDouble(v)).stripTrailingZeros().toPlainString();
                }
                catch (NumberFormatException e) {
                    // not a number: as written
                }
                m.put(t.substring(0, i), v);
            }
        }
        return m;
    }
}
