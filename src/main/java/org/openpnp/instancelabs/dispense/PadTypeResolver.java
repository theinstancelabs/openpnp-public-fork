package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package id (+ optional footprint pad) -> current pad type. Order: a mapping for some pads of the
 * package that names this pad (package_pad_type with pad names, profile calibration design §5.2),
 * the whole-package mapping, then package_pattern regexes with newer (user-added) types first, the
 * QFN/DFN centre-pad rule refining the perimeter pattern.
 */
public class PadTypeResolver {
    private final List<PadType> types;
    private final Map<String, String> packageMap;
    /** Per-pad mappings: package id -> pad name -> {type key, the mapping's joined pad names}. */
    private final Map<String, Map<String, String[]>> padMap = new HashMap<>();
    private final Set<String> pastePartial = new TreeSet<>();
    /** The tip setup the types are composed for and its version (§22.2), or null: the types as stored. */
    private String setupKey;
    private Integer setupVersion;

    /**
     * @param packageMap {@link DispenseDb#packageMappings()}: the package id (whole package) or
     *        {@code packageId#names} (some of its pads, comma-joined) -> type key
     */
    public PadTypeResolver(List<PadType> currentTypes, Map<String, String> packageMap) {
        this.types = new ArrayList<>(currentTypes);
        this.packageMap = packageMap == null ? Collections.emptyMap() : packageMap;
        for (Map.Entry<String, String> e : this.packageMap.entrySet()) {
            int h = e.getKey().lastIndexOf('#'); // pad names never hold '#' (DispenseDb.joinPadNames)
            if (h <= 0) {
                continue;
            }
            String names = e.getKey().substring(h + 1);
            Map<String, String[]> pads = padMap.computeIfAbsent(e.getKey().substring(0, h), k -> new HashMap<>());
            for (String n : DispenseDb.splitPadNames(names)) {
                pads.put(n, new String[] {e.getValue(), names});
            }
        }
    }

    /**
     * The packages whose dispensable pads are pasted although another pad of the part cannot be
     * dispensed ({@link DispenseDb#pastePartialPackages()}, main-thread decision 4). Part of the
     * {@link #fingerprint()}: switching it changes which pads a plan holds back.
     */
    public PadTypeResolver withPastePartial(Collection<String> packageIds) {
        pastePartial.clear();
        if (packageIds != null) {
            pastePartial.addAll(packageIds);
        }
        return this;
    }

    public Set<String> pastePartial() {
        return Collections.unmodifiableSet(pastePartial);
    }

    /**
     * The tip setup the types are composed for and its version (tip setups design §22.2): part of the
     * {@link #fingerprint()}, so mounting another setup or correcting the mounted one makes plans stale.
     */
    public PadTypeResolver withSetup(String key, Integer version) {
        this.setupKey = key;
        this.setupVersion = version;
        return this;
    }

    /** The current pad types this resolver chooses from. */
    public List<PadType> types() {
        return Collections.unmodifiableList(types);
    }

    /**
     * Identity of what this resolver can answer: every pad type with its versions (tip setups design §22.2:
     * {@code key:padVersion/version@setup}, {@code ~} for a stand-in's version; an edit of the pattern or status is a
     * new version), every package mapping (per-pad ones by their names), the packages pasted partly and the tip setup
     * with its version. A plan made with another resolver may resolve its packages differently.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (PadType t : types) {
            sb.append(t.typeKey).append(':').append(t.padVersion != null ? t.padVersion : t.version).append('/')
                    .append(t.standIn ? "~" : Integer.toString(t.version)).append('@')
                    .append(t.setupKey != null ? t.setupKey : org.openpnp.instancelabs.padprofile.Tip.setupOf(t.tipKey))
                    .append(';');
        }
        sb.append('|');
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(packageMap).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        if (!pastePartial.isEmpty()) {
            sb.append("|partial:").append(String.join(";", pastePartial));
        }
        if (setupKey != null) {
            sb.append("|tip:").append(setupKey).append('@').append(setupVersion);
        }
        return sb.toString();
    }

    public PadType byKey(String key) {
        for (PadType t : types) {
            if (t.typeKey.equals(key)) {
                return t;
            }
        }
        return null;
    }

    /** How a pad type was chosen: the type (null if none) and the rule that chose it. */
    public static final class Resolution {
        public static final String PAD_MAP = "pad-map";
        public static final String PACKAGE_MAP = "package-map";
        public static final String CENTRE_PAD = "centre-pad";
        public static final String PATTERN = "pattern";
        public static final String NONE = "none";

        public final PadType type;
        /** {@link #PAD_MAP}, {@link #PACKAGE_MAP}, {@link #CENTRE_PAD}, {@link #PATTERN} or {@link #NONE}. */
        public final String rule;
        /**
         * pad-map and package-map: the mapped type key; centre-pad: the type whose pattern matched; pattern: the
         * pattern.
         */
        public final String detail;
        /**
         * pad-map: the mapping's pad names (comma-joined); centre-pad and pattern: the text the pattern found in the
         * package id (separators trimmed); else null.
         */
        public final String matched;

        Resolution(PadType type, String rule, String detail, String matched) {
            this.type = type;
            this.rule = rule;
            this.detail = detail;
            this.matched = matched;
        }
    }

    /**
     * @param packageId OpenPnP package id
     * @param padX footprint pad centre (mm) or NaN when not from a footprint
     * @param padY footprint pad centre (mm) or NaN
     * @param padAreaMm2 footprint pad area or NaN
     */
    public PadType resolve(String packageId, double padX, double padY, double padAreaMm2) {
        return explain(packageId, null, padX, padY, padAreaMm2).type;
    }

    /** As above for a named pad of the package: a mapping for some of its pads may name it. */
    public PadType resolve(String packageId, String padName, double padX, double padY, double padAreaMm2) {
        return explain(packageId, padName, padX, padY, padAreaMm2).type;
    }

    /** As {@link #resolve}, with the rule that chose the type (the pad inspector and the import preview). */
    public Resolution explain(String packageId, double padX, double padY, double padAreaMm2) {
        return explain(packageId, null, padX, padY, padAreaMm2);
    }

    /** As above for a named pad ({@code padName} null: per-pad mappings are not looked at). */
    public Resolution explain(String packageId, String padName, double padX, double padY, double padAreaMm2) {
        if (packageId == null) {
            return new Resolution(null, Resolution.NONE, null, null);
        }
        String[] perPad = padName == null ? null : padMap.getOrDefault(packageId, Collections.emptyMap()).get(padName);
        if (perPad != null) {
            return new Resolution(byKey(perPad[0]), Resolution.PAD_MAP, perPad[0], perPad[1]);
        }
        String mapped = packageMap.get(packageId);
        if (mapped != null) {
            return new Resolution(byKey(mapped), Resolution.PACKAGE_MAP, mapped, null);
        }
        PadType byPattern = null;
        String found = null;
        for (int i = types.size() - 1; i >= 0; i--) {
            PadType t = types.get(i);
            if (t.packagePattern != null && !t.packagePattern.isEmpty()) {
                Matcher m = Pattern.compile(t.packagePattern, Pattern.CASE_INSENSITIVE).matcher(packageId);
                if (m.find()) {
                    byPattern = t;
                    found = trimSeparators(m.group());
                    break;
                }
            }
        }
        if (byPattern == null) {
            return new Resolution(null, Resolution.NONE, null, null);
        }
        if ("qfn-dfn-perimeter".equals(byPattern.typeKey)
                && !Double.isNaN(padX) && Math.hypot(padX, padY) < 0.05 && padAreaMm2 >= 1.0) {
            PadType centre = byKey("qfn-centre");
            if (centre != null) {
                return new Resolution(centre, Resolution.CENTRE_PAD, byPattern.typeKey, found);
            }
        }
        return new Resolution(byPattern, Resolution.PATTERN, byPattern.packagePattern, found);
    }

    /**
     * The seeded patterns match a separator on each side ({@code (^|[^0-9])0603([^0-9]|$)} finds
     * {@code _0603_}): the text shown is what lies between them. Unchanged if nothing would be left.
     */
    static String trimSeparators(String s) {
        int a = 0, b = s.length();
        while (a < b && !Character.isLetterOrDigit(s.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetterOrDigit(s.charAt(b - 1))) {
            b--;
        }
        return a < b ? s.substring(a, b) : s;
    }
}
