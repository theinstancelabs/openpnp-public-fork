package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Package id (+ optional footprint pad) -> current pad type. Order: explicit package mapping
 * (package_pad_type), then the QFN/DFN centre-pad rule, then package_pattern regexes with newer
 * (user-added) types first.
 */
public class PadTypeResolver {
    private final List<PadType> types;
    private final Map<String, String> packageMap;

    public PadTypeResolver(List<PadType> currentTypes, Map<String, String> packageMap) {
        this.types = new ArrayList<>(currentTypes);
        this.packageMap = packageMap == null ? Collections.emptyMap() : packageMap;
    }

    /**
     * Identity of what this resolver can answer: every pad type with its version (an edit of the pattern or status is a
     * new version) and every package mapping. A plan made with another resolver may resolve its packages differently.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder();
        for (PadType t : types) {
            sb.append(t.typeKey).append(':').append(t.version).append(';');
        }
        sb.append('|');
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(packageMap).entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
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

    /**
     * @param packageId OpenPnP package id
     * @param padX footprint pad centre (mm) or NaN when not from a footprint
     * @param padY footprint pad centre (mm) or NaN
     * @param padAreaMm2 footprint pad area or NaN
     */
    public PadType resolve(String packageId, double padX, double padY, double padAreaMm2) {
        if (packageId == null) {
            return null;
        }
        String mapped = packageMap.get(packageId);
        if (mapped != null) {
            return byKey(mapped);
        }
        PadType byPattern = null;
        for (int i = types.size() - 1; i >= 0; i--) {
            PadType t = types.get(i);
            if (t.packagePattern != null && !t.packagePattern.isEmpty()
                    && Pattern.compile(t.packagePattern, Pattern.CASE_INSENSITIVE).matcher(packageId).find()) {
                byPattern = t;
                break;
            }
        }
        if (byPattern != null && "qfn-dfn-perimeter".equals(byPattern.typeKey)
                && !Double.isNaN(padX) && Math.hypot(padX, padY) < 0.05 && padAreaMm2 >= 1.0) {
            PadType centre = byKey("qfn-centre");
            if (centre != null) {
                return centre;
            }
        }
        return byPattern;
    }
}
