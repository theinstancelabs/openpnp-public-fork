package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportProposal.PackageP;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;

/** Package and part ids of an import (design §3.5): case-insensitive, never changing an existing package. */
public class IdAllocatorTest {
    static PadP pad(String name, double x, double w) {
        PadP p = new PadP();
        p.name = name;
        p.x = x;
        p.w = w;
        p.h = 0.95;
        p.roundness = 50;
        return p;
    }

    static List<PadP> chip(double w) {
        return new ArrayList<>(Arrays.asList(pad("1", -0.825, w), pad("2", 0.825, w)));
    }

    /** One package variant with its placements (ref, value). */
    static void add(ImportProposal p, String baseId, List<PadP> pads, String... refValue) {
        PackageP pk = new PackageP();
        pk.baseId = baseId;
        pk.variant = 1;
        for (PackageP o : p.packages) {
            pk.variant += o.baseId.equals(baseId) ? 1 : 0;
        }
        pk.pads.addAll(pads);
        p.packages.add(pk);
        for (int i = 0; i + 1 < refValue.length; i += 2) {
            PlacementP pl = new PlacementP();
            pl.id = refValue[i];
            pl.value = refValue[i + 1];
            pl.type = "Placement";
            pl.packageIndex = p.packages.size() - 1;
            pk.refs.add(pl.id);
            p.placements.add(pl);
        }
    }

    @Test
    public void newIdsInAnEmptyConfiguration() throws Exception {
        ImportProposal p = new ImportProposal();
        add(p, "R_0603_1608Metric", chip(0.8), "R1", "R_Small", "R2", "R_Small", "R3", "10k");
        IdAllocator.Result r = IdAllocator.allocate(p, new IdAllocator.Snapshot());
        assertEquals("R_0603_1608Metric", r.packages.get(0).id);
        assertEquals(IdAllocator.NEW, r.packages.get(0).state);
        assertNull(r.packages.get(0).why);
        assertEquals(2, r.parts.size());
        assertEquals("R_0603_1608Metric-R_Small", r.parts.get(0).id);
        assertEquals(Arrays.asList("R1", "R2"), r.parts.get(0).refs);
        assertEquals("R_0603_1608Metric-10k", r.partOf(2).id);
        assertTrue(r.decisions().matches("[0-9a-f]{16}"));
    }

    @Test
    public void aPadlessPackageIsNeverAdoptedAndItsPartIsLeftAlone() throws Exception {
        // the live ftp case: the .pos import made R_0603_1608Metric without pads
        IdAllocator.Snapshot s = new IdAllocator.Snapshot().addPackage("R_0603_1608Metric", null)
                .addPart("R_0603_1608Metric-R_Small", "R_0603_1608Metric");
        ImportProposal p = new ImportProposal();
        add(p, "R_0603_1608Metric", chip(0.8), "R1", "R_Small");
        IdAllocator.Result r = IdAllocator.allocate(p, s);
        assertEquals("R_0603_1608Metric~2", r.packages.get(0).id);
        assertEquals(IdAllocator.SUFFIXED, r.packages.get(0).state);
        assertEquals("R_0603_1608Metric exists in OpenPnP without pads", r.packages.get(0).why);
        assertEquals("R_0603_1608Metric~2-R_Small", r.parts.get(0).id);
        assertEquals(IdAllocator.NEW, r.parts.get(0).state);
    }

    @Test
    public void twoVariantsOfOneNameNeverShareAnId() throws Exception {
        // a pad-less K, then two variants of K in the file: K~2 and K~3 (not both K~2)
        IdAllocator.Snapshot s = new IdAllocator.Snapshot().addPackage("K", null);
        ImportProposal p = new ImportProposal();
        add(p, "K", chip(0.8), "U1", "A");
        add(p, "K", chip(0.9), "U2", "A");
        IdAllocator.Result r = IdAllocator.allocate(p, s);
        assertEquals("K~2", r.packages.get(0).id);
        assertEquals("K~3", r.packages.get(1).id);
        assertEquals("K exists in OpenPnP without pads", r.packages.get(1).why, "the first reason");
        assertEquals("K~2-A", r.parts.get(0).id);
        assertEquals("K~3-A", r.parts.get(1).id);
        r = IdAllocator.allocate(p, new IdAllocator.Snapshot());
        assertEquals("K~2", r.packages.get(1).id);
        assertEquals("another footprint in this file is also called K", r.packages.get(1).why);
    }

    @Test
    public void aMatchingPackageIsReusedWhateverTheCase() throws Exception {
        List<PadP> mm = chip(0.8);
        mm.get(0).x += 0.004; // within 0.01 mm
        mm.get(1).rotation = 360; // the same angle
        IdAllocator.Snapshot s = new IdAllocator.Snapshot().addPackage("r_0603_1608metric", mm)
                .addPart("r_0603_1608metric-R_Small", "r_0603_1608metric");
        ImportProposal p = new ImportProposal();
        add(p, "R_0603_1608Metric", chip(0.8), "R1", "R_Small", "R2", "1k");
        IdAllocator.Result r = IdAllocator.allocate(p, s);
        assertEquals("r_0603_1608metric", r.packages.get(0).id, "OpenPnP's own spelling");
        assertEquals(IdAllocator.REUSE, r.packages.get(0).state);
        assertEquals("r_0603_1608metric-R_Small", r.parts.get(0).id);
        assertEquals(IdAllocator.REUSE, r.parts.get(0).state);
        assertEquals("r_0603_1608metric-1k", r.parts.get(1).id);
        assertEquals(IdAllocator.NEW, r.parts.get(1).state);
    }

    @Test
    public void footprintsDifferBeyondTheTolerances() {
        assertTrue(IdAllocator.sameFootprint(chip(0.8), chip(0.809)));
        assertTrue(!IdAllocator.sameFootprint(chip(0.8), chip(0.812)));
        List<PadP> renamed = chip(0.8);
        renamed.get(1).name = "3";
        assertTrue(!IdAllocator.sameFootprint(chip(0.8), renamed));
        List<PadP> round = chip(0.8);
        round.get(0).roundness = 49.6;
        assertTrue(IdAllocator.sameFootprint(chip(0.8), round));
        round.get(0).roundness = 49;
        assertTrue(!IdAllocator.sameFootprint(chip(0.8), round));
        List<PadP> turned = chip(0.8);
        turned.get(0).rotation = 0.02;
        assertTrue(!IdAllocator.sameFootprint(chip(0.8), turned));
        assertTrue(!IdAllocator.sameFootprint(chip(0.8), new ArrayList<>()));
    }

    @Test
    public void partsAreKeyedByPackageAndValue() throws Exception {
        // SOT-23 + "5-X" and SOT-23-5 + "X" both spell SOT-23-5-X
        ImportProposal p = new ImportProposal();
        add(p, "SOT-23", chip(0.8), "U1", "5-X");
        add(p, "SOT-23-5", chip(0.6), "U2", "X", "U3", "X");
        IdAllocator.Result r = IdAllocator.allocate(p, new IdAllocator.Snapshot());
        assertEquals("SOT-23-5-X", r.parts.get(0).id);
        assertEquals("SOT-23-5-X~2", r.parts.get(1).id);
        assertEquals(IdAllocator.SUFFIXED, r.parts.get(1).state);
        assertEquals(Arrays.asList("U2", "U3"), r.parts.get(1).refs);
        // a part of another package in OpenPnP is never taken over
        IdAllocator.Snapshot s = new IdAllocator.Snapshot().addPart("SOT-23-5-X", "SOMETHING-ELSE");
        r = IdAllocator.allocate(p, s);
        assertEquals("SOT-23-5-X~2", r.parts.get(0).id);
        assertEquals("SOT-23-5-X~3", r.parts.get(1).id);
        // a blank value: the package id alone
        ImportProposal q = new ImportProposal();
        add(q, "TP", chip(0.8), "TP1", "");
        assertEquals("TP", IdAllocator.allocate(q, new IdAllocator.Snapshot()).parts.get(0).id);
    }

    @Test
    public void ninetyNineVariantsExhaustTheIds() {
        IdAllocator.Snapshot s = new IdAllocator.Snapshot().addPackage("K", null);
        for (int k = 2; k <= IdAllocator.MAX_SUFFIX; k++) {
            s.addPackage("K~" + k, chip(1.5));
        }
        ImportProposal p = new ImportProposal();
        add(p, "K", chip(0.8), "U1", "A");
        IdAllocator.Exhausted e = assertThrows(IdAllocator.Exhausted.class, () -> IdAllocator.allocate(p, s));
        assertEquals("K already has 99 variants in OpenPnP: remove unused packages in OpenPnP's Packages tab.",
                e.getMessage());
    }

    @Test
    public void decisionsFollowTheIdsAndStates() throws Exception {
        ImportProposal p = new ImportProposal();
        add(p, "R", chip(0.8), "R1", "A");
        String fresh = IdAllocator.allocate(p, new IdAllocator.Snapshot()).decisions();
        assertEquals(fresh, IdAllocator.allocate(p, new IdAllocator.Snapshot()).decisions(), "deterministic");
        String reuse = IdAllocator.allocate(p, new IdAllocator.Snapshot().addPackage("R", chip(0.8))).decisions();
        assertNotEquals(fresh, reuse, "same ids, another state");
        String suffixed = IdAllocator.allocate(p, new IdAllocator.Snapshot().addPackage("R", null)).decisions();
        assertNotEquals(fresh, suffixed);
    }
}
