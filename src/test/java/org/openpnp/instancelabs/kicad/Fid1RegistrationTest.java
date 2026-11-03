package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.kicad.ImportProposal.PlacementP;

/**
 * The hand-moved FID1 is not cosmetic (design §2.6): fit the 3-point board→machine affine of the bench's r6
 * registration once with ftp.board.xml's fiducials and once with the KiCad proposal's. ftp.board.xml's FID1 shrinks
 * the board 0.5 % in Y; KiCad's brings the Y scale back near 1, and the registered low rows move by 0.254 mm.
 */
public class Fid1RegistrationTest {
    /** Measured machine positions of the fiducials (~/instancelabs/runs/board_registration_r6.json). */
    static final Map<String, double[]> MEASURED = new HashMap<>();

    static {
        MEASURED.put("FID1", new double[] {358.301, 118.715});
        MEASURED.put("FID2", new double[] {308.719, 184.241});
        MEASURED.put("FID3", new double[] {358.783, 183.675});
    }

    /** {a, b, c, d, e, f}: x' = a·x + b·y + c, y' = d·x + e·y + f, exact through the three fiducials. */
    static double[] fit(Map<String, double[]> board) {
        String[] ids = {"FID1", "FID2", "FID3"};
        double[][] m = new double[3][];
        for (int i = 0; i < 3; i++) {
            double[] b = board.get(ids[i]);
            m[i] = new double[] {b[0], b[1], 1};
        }
        double[] rx = new double[3];
        double[] ry = new double[3];
        for (int i = 0; i < 3; i++) {
            rx[i] = MEASURED.get(ids[i])[0];
            ry[i] = MEASURED.get(ids[i])[1];
        }
        double[] x = cramer(m, rx);
        double[] y = cramer(m, ry);
        return new double[] {x[0], x[1], x[2], y[0], y[1], y[2]};
    }

    static double[] cramer(double[][] m, double[] r) {
        double d = det(m);
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] k = new double[3][3];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    k[i][j] = j == c ? r[i] : m[i][j];
                }
            }
            out[c] = det(k) / d;
        }
        return out;
    }

    static double det(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    static double[] apply(double[] a, double x, double y) {
        return new double[] {a[0] * x + a[1] * y + a[2], a[3] * x + a[4] * y + a[5]};
    }

    /** The length of the image of the board's Y axis. */
    static double yScale(double[] a) {
        return Math.hypot(a[1], a[4]);
    }

    @Test
    public void kicadsFid1RemovesTheYShrink() throws Exception {
        Map<String, KicadFtpTest.XmlPlacement> xml = new HashMap<>();
        Map<String, double[]> xmlFids = new HashMap<>();
        for (KicadFtpTest.XmlPlacement p : KicadFtpTest.boardXml()) {
            xml.put(p.id, p);
            if ("Fiducial".equals(p.type)) {
                xmlFids.put(p.id, new double[] {p.x, p.y});
            }
        }
        ImportProposal proposal = Fixtures.load(Fixtures.FTP).proposal;
        Map<String, double[]> kicadFids = new HashMap<>();
        for (PlacementP p : proposal.placements) {
            if ("Fiducial".equals(p.type)) {
                kicadFids.put(p.id, new double[] {p.x, p.y});
            }
        }
        double[] fromXml = fit(xmlFids);
        double[] fromKicad = fit(kicadFids);
        assertEquals(0.9947, yScale(fromXml), 0.0005);
        assertEquals(0.9994, yScale(fromKicad), 0.0005);

        // the same board registered both ways: placement centres differ by up to 0.254 mm, on the low row (R19 …)
        double worst = 0;
        String worstId = null;
        double worstPad = 0;
        List<String> log = new ArrayList<>();
        for (PlacementP k : proposal.placements) {
            if (!"Placement".equals(k.type)) {
                continue;
            }
            KicadFtpTest.XmlPlacement x = xml.get(k.id);
            double[] a = apply(fromXml, x.x, x.y);
            double[] b = apply(fromKicad, k.x, k.y);
            double d = Math.hypot(a[0] - b[0], a[1] - b[1]);
            if (d > worst + 1e-9) {
                worst = d;
                worstId = k.id;
            }
            double t = Math.toRadians(k.rotation);
            for (ImportProposal.PadP pad : proposal.packages.get(k.packageIndex).pads) {
                double px = pad.x * Math.cos(t) - pad.y * Math.sin(t);
                double py = pad.x * Math.sin(t) + pad.y * Math.cos(t);
                double[] pa = apply(fromXml, x.x + px, x.y + py);
                double[] pb = apply(fromKicad, k.x + px, k.y + py);
                double dp = Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
                worstPad = Math.max(worstPad, dp);
                log.add(String.format(Locale.ROOT, "%s.%s %.3f", k.id, pad.name, dp));
            }
        }
        assertEquals(0.254, worst, 0.002);
        double[] r19 = apply(fromXml, xml.get("R19").x, xml.get("R19").y);
        double[] r19k = apply(fromKicad, proposal.placement("R19").x, proposal.placement("R19").y);
        assertEquals(worst, Math.hypot(r19[0] - r19k[0], r19[1] - r19k[1]), 1e-6, "R19 (26.25, 17.0) is on that row");
        assertTrue(worstPad < 0.26, "pads: " + worstPad);
        System.out.println("Fid1RegistrationTest: Y scale " + yScale(fromXml) + " (ftp.board.xml) vs "
                + yScale(fromKicad) + " (KiCad); worst placement " + worstId + " " + worst + " mm; worst pad "
                + worstPad + " mm");
        System.out.println("Fid1RegistrationTest per pad (mm): " + String.join(", ", log));
    }
}
