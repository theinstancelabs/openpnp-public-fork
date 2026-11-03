package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** PadTypeResolver.explain: the rule behind every pad type, and resolve unchanged. */
public class PadTypeResolverExplainTest {
    static List<PadType> seeds() {
        List<PadType> l = new ArrayList<>();
        for (PadType t : DispenseDb.seedPadTypes()) {
            t.version = 1;
            t.status = PadTypeStatus.PROVISIONAL;
            l.add(t);
        }
        return l;
    }

    @Test
    public void patternGivesThePatternAndTheTextItFound() {
        PadTypeResolver r = new PadTypeResolver(seeds(), null);
        PadTypeResolver.Resolution e = r.explain("R_0603_1608Metric~2", Double.NaN, Double.NaN, Double.NaN);
        assertEquals("chip-0603", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PATTERN, e.rule);
        assertEquals("(^|[^0-9])0603([^0-9]|$)|1608Metric", e.detail);
        assertEquals("0603", e.matched, "the separators the pattern matched around 0603 are not shown");
        e = r.explain("SOT-23~2", Double.NaN, Double.NaN, Double.NaN);
        assertEquals("sot-23", e.type.typeKey);
        assertEquals("SOT-23", e.matched);
        e = r.explain("C_1608Metric", Double.NaN, Double.NaN, Double.NaN);
        assertEquals("chip-0603", e.type.typeKey);
        assertEquals("1608Metric", e.matched);
        // a footprint pad off the centre of a QFN is a perimeter pad, by pattern
        e = r.explain("QFN-16_3x3mm", -1.45, 0.75, 0.2);
        assertEquals("qfn-dfn-perimeter", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PATTERN, e.rule);
        assertEquals("QFN|DFN", e.detail);
        assertEquals("QFN", e.matched);
    }

    @Test
    public void newerTypesWinAndAreExplained() {
        List<PadType> types = seeds();
        PadType hd = new PadType("custom-0603-hd", "Custom", "0603 HD", 0.8, 0.95, PadType.Layout.TWO_TERMINAL, 1.65,
                1.0, "0603", "user");
        hd.version = 1;
        hd.status = PadTypeStatus.PROVISIONAL;
        types.add(hd);
        PadTypeResolver.Resolution e = new PadTypeResolver(types, null).explain("R_0603_1608Metric", Double.NaN,
                Double.NaN, Double.NaN);
        assertSame(hd, e.type);
        assertEquals("0603", e.detail);
        assertEquals("0603", e.matched);
    }

    @Test
    public void packageMapGivesTheMappedKey() {
        Map<String, String> map = Collections.singletonMap("R_0603_1608Metric", "chip-0805");
        PadTypeResolver r = new PadTypeResolver(seeds(), map);
        PadTypeResolver.Resolution e = r.explain("R_0603_1608Metric", Double.NaN, Double.NaN, Double.NaN);
        assertEquals("chip-0805", e.type.typeKey, "a mapping wins over the pattern");
        assertEquals(PadTypeResolver.Resolution.PACKAGE_MAP, e.rule);
        assertEquals("chip-0805", e.detail);
        assertNull(e.matched);
        // the mapping is by exact id: the suffixed copy of an import falls back to the pattern
        e = r.explain("R_0603_1608Metric~2", Double.NaN, Double.NaN, Double.NaN);
        assertEquals(PadTypeResolver.Resolution.PATTERN, e.rule);
        assertEquals("chip-0603", e.type.typeKey);
        // a mapping to a type that is gone resolves to nothing, as before
        e = new PadTypeResolver(seeds(), Collections.singletonMap("X", "retired")).explain("X", 0, 0, 1);
        assertNull(e.type);
        assertEquals(PadTypeResolver.Resolution.PACKAGE_MAP, e.rule);
        assertEquals("retired", e.detail);
    }

    @Test
    public void centrePadNamesThePerimeterTypeItReplaced() {
        PadTypeResolver r = new PadTypeResolver(seeds(), null);
        PadTypeResolver.Resolution e = r.explain("QFN-16_3x3mm", 0, 0, 2.89);
        assertEquals("qfn-centre", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.CENTRE_PAD, e.rule);
        assertEquals("qfn-dfn-perimeter", e.detail);
        assertEquals("QFN", e.matched);
        // under 1.0 mm^2 at the origin: still a perimeter pad
        assertEquals(PadTypeResolver.Resolution.PATTERN, r.explain("DFN-8", 0, 0, 0.9).rule);
    }

    @Test
    public void noneWhenNothingMatches() {
        PadTypeResolver r = new PadTypeResolver(seeds(), null);
        for (String id : new String[] {"WSON-8", null}) {
            PadTypeResolver.Resolution e = r.explain(id, Double.NaN, Double.NaN, Double.NaN);
            assertNull(e.type);
            assertEquals(PadTypeResolver.Resolution.NONE, e.rule);
            assertNull(e.detail);
            assertNull(e.matched);
        }
    }

    /** PadTypeResolver.resolve as it was before explain (the oracle for "identical behaviour"). */
    static PadType resolveBefore(List<PadType> types, Map<String, String> packageMap, String packageId, double padX,
            double padY, double padAreaMm2) {
        if (packageId == null) {
            return null;
        }
        String mapped = packageMap.get(packageId);
        if (mapped != null) {
            return byKey(types, mapped);
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
            PadType centre = byKey(types, "qfn-centre");
            if (centre != null) {
                return centre;
            }
        }
        return byPattern;
    }

    static PadType byKey(List<PadType> types, String key) {
        for (PadType t : types) {
            if (t.typeKey.equals(key)) {
                return t;
            }
        }
        return null;
    }

    @Test
    public void resolveIsExplainsTypeAndUnchanged() {
        List<PadType> types = seeds();
        String[] ids = {"R_0402_1005Metric", "R_0603_1608Metric", "R_0603_1608Metric~2", "LED_0603_1608Metric",
            "C_0805_2012Metric", "R_1206_3216Metric", "D_SOD-123", "SOT-23", "SOT-23-5", "SOT-23~2",
            "SOIC-8_3.9x4.9mm_P1.27mm", "TSSOP-20", "MSOP-10", "LQFP-48", "QFN-16_3x3mm", "DFN-8", "WSON-8",
            "Fiducial_1mm_Mask3mm", "custom", "", null};
        double[][] pads = {{Double.NaN, Double.NaN, Double.NaN}, {0, 0, 2.89}, {0, 0, 0.5}, {-1.45, 0.75, 0.2},
            {0.01, 0.01, 1.0}};
        List<Map<String, String>> maps = List.of(Collections.emptyMap(), Collections.singletonMap("SOT-23-5", "sot-23"),
                Collections.singletonMap("QFN-16_3x3mm", "qfn-dfn-perimeter"));
        List<PadType> withoutCentre = new ArrayList<>(types);
        withoutCentre.removeIf(t -> t.typeKey.equals("qfn-centre"));
        for (List<PadType> ts : List.of(types, withoutCentre)) {
            for (Map<String, String> map : maps) {
                PadTypeResolver r = new PadTypeResolver(ts, map);
                for (String id : ids) {
                    for (double[] p : pads) {
                        PadType before = resolveBefore(ts, map, id, p[0], p[1], p[2]);
                        assertSame(before, r.resolve(id, p[0], p[1], p[2]), id);
                        assertSame(before, r.explain(id, p[0], p[1], p[2]).type, id);
                    }
                }
            }
        }
    }

    @Test
    public void trimSeparators() {
        assertEquals("0603", PadTypeResolver.trimSeparators("_0603_"));
        assertEquals("SOT-23", PadTypeResolver.trimSeparators("SOT-23~"));
        assertEquals("__", PadTypeResolver.trimSeparators("__"));
        assertEquals("", PadTypeResolver.trimSeparators(""));
    }
}
