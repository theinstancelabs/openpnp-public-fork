package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.PadP;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;

/**
 * KiCad itself as the oracle for the paste rules (design §6.2): every aperture of nano's proposal, placed on the
 * board, must be one of the openings of KiCad's own paste Gerber (NanoV3.3-F_Paste.gbr, written by KiCad 10.0.5 from
 * the same board): centre, sides, corner radius and rotation.
 */
public class NanoPasteOracleTest {
    static final double TOL_MM = 0.001;
    static final double TOL_DEG = 0.01;

    @Test
    public void everyApertureIsAnOpeningOfKiCadsPasteGerber() throws Exception {
        GerberApertures g = GerberApertures.read(
                new String(Fixtures.bytes("NanoV3.3-F_Paste.gbr"), StandardCharsets.US_ASCII));
        assertEquals(54, g.flashes.size());
        assertEquals(1, g.regions.size());
        List<GerberApertures.Shape> shapes = new ArrayList<>(g.flashes);
        shapes.addAll(g.regions);

        Fixtures.Loaded nano = Fixtures.load(Fixtures.NANO);
        ImportProposal p = nano.proposal;
        Set<GerberApertures.Shape> used = new HashSet<>();
        int apertures = 0;
        double worst = 0;
        for (PlacementP pl : p.placements) {
            if (!"Placement".equals(pl.type)) {
                continue;
            }
            double t = Math.toRadians(pl.rotation);
            for (PadP pad : p.packages.get(pl.packageIndex).pads) {
                apertures++;
                // board frame (§2.6) → Gerber: X = board x + minX, Y = −KiCad Y = board y − maxY
                double gx = pl.x + pad.x * Math.cos(t) - pad.y * Math.sin(t) + p.minX;
                double gy = pl.y + pad.x * Math.sin(t) + pad.y * Math.cos(t) - p.maxY;
                GerberApertures.Shape s = nearest(shapes, gx, gy);
                String what = pl.id + " pad " + pad.name;
                assertNotNull(s, what);
                double d = Math.hypot(s.x - gx, s.y - gy);
                worst = Math.max(worst, d);
                assertTrue(d <= TOL_MM, what + ": centre " + d + " mm from KiCad's opening");
                assertTrue(used.add(s), what + ": KiCad's opening already matched");
                if (s.ref != null) {
                    assertEquals(s.ref, pl.ref, what);
                }
                double rot = pl.rotation + pad.rotation;
                boolean square = Math.abs(pad.w - pad.h) < TOL_MM;
                boolean same = Math.abs(s.a - pad.w) <= TOL_MM && Math.abs(s.b - pad.h) <= TOL_MM
                        && angleClose(s.angle, rot, square ? 90 : 180);
                boolean turned = Math.abs(s.a - pad.h) <= TOL_MM && Math.abs(s.b - pad.w) <= TOL_MM
                        && angleClose(s.angle, rot + 90, square ? 90 : 180);
                assertTrue(same || turned, what + ": " + pad.w + " × " + pad.h + " at " + rot + "° vs KiCad "
                        + s.a + " × " + s.b + " at " + s.angle + "°");
                double radius = Math.min(pad.w, pad.h) * pad.roundness / 200;
                assertEquals(s.radius, radius, TOL_MM, what + ": corner radius");
            }
        }
        assertEquals(55, apertures);
        assertEquals(shapes.size(), used.size());
        System.out.println("NanoPasteOracleTest: 55 apertures, worst centre distance " + worst + " mm");
    }

    @Test
    public void nanoRadiusIsKicadsShrunkCorner() throws Exception {
        // %ADD11RoundRect,0.103800…: copper corner 0.18 mm shrunk by the pad's paste margin −0.0762 mm
        Fixtures.Loaded nano = Fixtures.load(Fixtures.NANO);
        PlacementP tx = nano.proposal.placement("TX0");
        PadP pad = nano.proposal.packages.get(tx.packageIndex).pads.get(0);
        assertEquals(1.2 - 0.1524, pad.w, 1e-9);
        assertEquals(0.1038, Math.min(pad.w, pad.h) * pad.roundness / 200, 1e-6);
    }

    private static GerberApertures.Shape nearest(List<GerberApertures.Shape> shapes, double x, double y) {
        GerberApertures.Shape best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (GerberApertures.Shape s : shapes) {
            double d = Math.hypot(s.x - x, s.y - y);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    static boolean angleClose(double a, double b, double period) {
        double d = ((a - b) % period + period) % period;
        return Math.min(d, period - d) <= TOL_DEG;
    }
}
