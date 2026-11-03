package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.KicadPcb.Footprint;
import org.openpnp.instancelabs.kicad.KicadPcb.Pad;
import org.openpnp.instancelabs.kicad.KicadPcb.Via;

/**
 * KiCad's drill files as the oracle for the board frame (design §6.2): every drilled pad of nano's footprints, top AND
 * bottom (J1/J2's 30 bottom-side pins included), placed by §2.6, is a hit of NanoV3.3-PTH.drl / -NPTH.drl with the
 * same diameter, and the hit counts match. KiCad stores a flipped footprint's pads already mirrored.
 */
public class NanoDrillOracleTest {
    static final double TOL_MM = 0.001;

    /** One Excellon hit: x, y (KiCad X, −KiCad Y), diameter; via = the tool is marked ViaDrill. */
    static final class Hit {
        double x;
        double y;
        double d;
        boolean via;
        boolean used;
    }

    static List<Hit> excellon(String name) throws Exception {
        String text = new String(Fixtures.bytes(name), StandardCharsets.US_ASCII);
        Map<String, Double> tools = new HashMap<>();
        Map<String, Boolean> vias = new HashMap<>();
        List<Hit> hits = new ArrayList<>();
        boolean nextIsVia = false;
        String tool = null;
        Pattern def = Pattern.compile("^(T\\d+)C([\\d.]+)$");
        Pattern hit = Pattern.compile("^X(-?[\\d.]+)Y(-?[\\d.]+)$");
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith(";")) {
                nextIsVia = line.contains("ViaDrill") || nextIsVia && !line.contains("AperFunction");
                continue;
            }
            Matcher m = def.matcher(line);
            if (m.matches()) {
                tools.put(m.group(1), Double.parseDouble(m.group(2)));
                vias.put(m.group(1), nextIsVia);
                nextIsVia = false;
                continue;
            }
            if (line.matches("^T\\d+$")) {
                tool = line;
                continue;
            }
            m = hit.matcher(line);
            if (m.matches()) {
                Hit h = new Hit();
                h.x = Double.parseDouble(m.group(1));
                h.y = Double.parseDouble(m.group(2));
                h.d = tools.get(tool);
                h.via = vias.get(tool);
                hits.add(h);
            }
        }
        return hits;
    }

    @Test
    public void footprintDrillsAreKiCadsHits() throws Exception {
        Fixtures.Loaded nano = Fixtures.load(Fixtures.NANO);
        List<Hit> pth = excellon("NanoV3.3-PTH.drl");
        List<Hit> npth = excellon("NanoV3.3-NPTH.drl");
        BoardFrame f = nano.frame;
        int plated = 0;
        int nonPlated = 0;
        int bottomPins = 0;
        for (Footprint fp : nano.pcb.footprints) {
            for (Pad p : fp.pads) {
                if (p.drillW <= 0) {
                    continue;
                }
                boolean np = "np_thru_hole".equals(p.type);
                double[] b = f.localToBoard(fp, p.x, p.y);
                // board frame → Excellon: X = board x + minX, Y = −KiCad Y = board y − maxY
                match(np ? npth : pth, b[0] + f.minX, b[1] - f.maxY, p.drillW, false, fp.ref + " pad " + p.name);
                if (np) {
                    nonPlated++;
                }
                else {
                    plated++;
                    bottomPins += fp.top ? 0 : 1;
                }
            }
        }
        assertEquals(30, bottomPins);
        assertEquals(pth.stream().filter(h -> !h.via).count(), plated);
        assertEquals(npth.size(), nonPlated);
        for (Via v : nano.pcb.vias) {
            double[] b = f.toBoard(v.x, v.y);
            match(pth, b[0] + f.minX, b[1] - f.maxY, v.drill, true, "via");
        }
        assertEquals(pth.stream().filter(h -> h.via).count(), nano.pcb.vias.size());
        assertTrue(pth.stream().allMatch(h -> h.used));
        assertTrue(npth.stream().allMatch(h -> h.used));
    }

    private static void match(List<Hit> hits, double x, double y, double d, boolean via, String what) {
        for (Hit h : hits) {
            if (!h.used && h.via == via && Math.hypot(h.x - x, h.y - y) <= TOL_MM) {
                assertEquals(h.d, d, TOL_MM, what + ": diameter");
                h.used = true;
                return;
            }
        }
        throw new AssertionError(what + ": no drill hit at X " + x + " Y " + y);
    }
}
