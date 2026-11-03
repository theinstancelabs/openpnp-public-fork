package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Configuration;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * Mappings for some pads of a package (profile calibration design §5.2): a mapping that names the
 * pad wins over the whole-package mapping, which wins over the pattern and its centre-pad rule; the
 * names are part of the fingerprint; a SOT-223's pins and tab get two types through the extractor.
 */
public class ResolverPadMapTest {
    @BeforeAll
    public static void setup() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("resolver-pad-map").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
    }

    static PadType type(String key, String pattern) {
        PadType t = new PadType(key, "Custom", key, 1.0, 1.0, PadType.Layout.FOOTPRINT_ONLY, null, 1.0, pattern, null);
        t.version = 1;
        t.status = PadTypeStatus.CHARACTERIZED;
        return t;
    }

    static List<PadType> types() {
        List<PadType> l = PadTypeResolverExplainTest.seeds();
        l.add(type("sot223-pins", null));
        l.add(type("sot223-tab", null));
        l.add(type("qfn-ep-own", null));
        return l;
    }

    @Test
    public void padMapThenPackageMapThenPatternThenCentreRule() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(DispenseDb.mappingKey("SOT-223-3_TabPin2", "4"), "sot223-tab");
        map.put("SOT-223-3_TabPin2", "sot223-pins");
        map.put(DispenseDb.mappingKey("QFN-16_3x3mm", "17"), "qfn-ep-own");
        PadTypeResolver r = new PadTypeResolver(types(), map);
        // the pad the mapping names
        PadTypeResolver.Resolution e = r.explain("SOT-223-3_TabPin2", "4", 0, 0, 7.6);
        assertEquals("sot223-tab", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PAD_MAP, e.rule);
        assertEquals("sot223-tab", e.detail);
        assertEquals("4", e.matched);
        // its other pads: the whole-package mapping
        e = r.explain("SOT-223-3_TabPin2", "1", -2.3, 3.15, 3.0);
        assertEquals("sot223-pins", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PACKAGE_MAP, e.rule);
        // no pad name (the old signature): the per-pad mappings are not looked at
        assertEquals("sot223-pins", r.resolve("SOT-223-3_TabPin2", 0, 0, 7.6).typeKey);
        // the QFN's centre pad by its own mapping, its perimeter by the pattern, as before
        e = r.explain("QFN-16_3x3mm", "17", 0, 0, 2.89);
        assertEquals("qfn-ep-own", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PAD_MAP, e.rule);
        e = r.explain("QFN-16_3x3mm", "1", -1.45, 0.75, 0.2);
        assertEquals("qfn-dfn-perimeter", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.PATTERN, e.rule);
        // without the pad mapping the centre rule picks the centre pad
        e = new PadTypeResolver(types(), new HashMap<>()).explain("QFN-16_3x3mm", "17", 0, 0, 2.89);
        assertEquals("qfn-centre", e.type.typeKey);
        assertEquals(PadTypeResolver.Resolution.CENTRE_PAD, e.rule);
        // a mapping to a type that is gone resolves to nothing
        e = new PadTypeResolver(types(), Map.of(DispenseDb.mappingKey("X", "1,2"), "gone")).explain("X", "2", 0, 0, 1);
        assertNull(e.type);
        assertEquals(PadTypeResolver.Resolution.PAD_MAP, e.rule);
        assertEquals("1,2", e.matched);
    }

    @Test
    public void fingerprintChangesWithThePadNames() {
        PadTypeResolver whole = new PadTypeResolver(types(), Map.of("SOT-223-3_TabPin2", "sot223-tab"));
        PadTypeResolver tab = new PadTypeResolver(types(), Map.of(DispenseDb.mappingKey("SOT-223-3_TabPin2", "4"),
                "sot223-tab"));
        PadTypeResolver tabAndPin = new PadTypeResolver(types(), Map.of(DispenseDb.mappingKey("SOT-223-3_TabPin2",
                "2,4"), "sot223-tab"));
        assertNotEquals(whole.fingerprint(), tab.fingerprint());
        assertNotEquals(tab.fingerprint(), tabAndPin.fingerprint());
        assertEquals(tab.fingerprint(), new PadTypeResolver(types(), Map.of(DispenseDb.mappingKey("SOT-223-3_TabPin2",
                "4"), "sot223-tab")).fingerprint());
    }

    static Board sot223Board() {
        Package pkg = new Package("SOT-223-3_TabPin2");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        double[][] pads = {{-2.3, -3.15, 1.0, 1.5}, {0, -3.15, 1.0, 1.5}, {2.3, -3.15, 1.0, 1.5}, {0, 3.15, 3.8, 2.0}};
        for (int i = 0; i < pads.length; i++) {
            Footprint.Pad p = new Footprint.Pad();
            p.setName(Integer.toString(i + 1));
            p.setX(pads[i][0]);
            p.setY(pads[i][1]);
            p.setWidth(pads[i][2]);
            p.setHeight(pads[i][3]);
            fp.addPad(p);
        }
        pkg.setFootprint(fp);
        Part part = new Part("REG");
        part.setPackage(pkg);
        Board b = new Board();
        b.setName("sot223");
        b.addPlacement(BoardPlanningTest.placement("U1", part, 10, 10, 0));
        return b;
    }

    /** SOT-223: the pins and the tab on two pad types through the extractor and the planner. */
    @Test
    public void sot223PinsAndTabOnTwoTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("SOT-223-3_TabPin2", "sot223-pins");
        map.put(DispenseDb.mappingKey("SOT-223-3_TabPin2", "4"), "sot223-tab");
        PadTypeResolver r = new PadTypeResolver(types(), map);
        BoardLocation bl = new BoardLocation(sot223Board());
        bl.setLocation(new Location(LengthUnit.Millimeters, 100, 50, 5.5, 0));
        Map<String, PadGeometry> m = BoardPlanningTest.byId(new BoardPadExtractor(r, true).extract(bl).pads);
        for (String pin : Arrays.asList("U1.1", "U1.2", "U1.3")) {
            assertEquals("sot223-pins", m.get(pin).padType.typeKey, pin);
            assertEquals(PadTypeResolver.Resolution.PACKAGE_MAP, m.get(pin).typeRule);
        }
        assertEquals("sot223-tab", m.get("U1.4").padType.typeKey);
        assertEquals(PadTypeResolver.Resolution.PAD_MAP, m.get("U1.4").typeRule);
        assertEquals("4", m.get("U1.4").typeRuleMatched);
        DispenseConfig c = GcodeStreamTest.toyConfig();
        DispensePlan p = new DispensePlanner(c, r).planBoard(bl, 100, 50);
        Map<String, String> byPad = new HashMap<>();
        for (DispensePlan.PlannedPad x : p.pads) {
            byPad.put(x.padId, x.typeKey + " " + x.typeRule);
        }
        assertEquals("sot223-tab pad-map", byPad.get("U1.4"));
        assertEquals("sot223-pins package-map", byPad.get("U1.2"));
    }

    /** Pads from a type's layout ("1", "2", ...) are named too: a per-pad mapping can take one of them. */
    @Test
    public void layoutPadsAreNamed() {
        List<PadType> types = PadTypeResolverExplainTest.seeds();
        PadType own = type("r0603-pad2", null);
        types.add(own);
        PadTypeResolver r = new PadTypeResolver(types, Map.of(DispenseDb.mappingKey("R_0603_1608Metric", "2"),
                "r0603-pad2"));
        Part part = new Part("R");
        part.setPackage(new Package("R_0603_1608Metric"));
        Board b = new Board();
        b.addPlacement(BoardPlanningTest.placement("R1", part, 10, 10, 0));
        BoardLocation bl = new BoardLocation(b);
        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 1.6, 0));
        Map<String, PadGeometry> m = BoardPlanningTest.byId(new BoardPadExtractor(r, true).extract(bl).pads);
        assertEquals("chip-0603", m.get("R1.1").padType.typeKey);
        assertEquals("r0603-pad2", m.get("R1.2").padType.typeKey);
        // the layout gives both pads the 0603 size
        assertEquals(0.8, m.get("R1.2").wMm, 1e-12);
    }
}
