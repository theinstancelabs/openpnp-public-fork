package org.openpnp.instancelabs.kicad;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A test-only reader for the paste Gerber KiCad writes (design §6.2): %FSLAX46Y46%, %MOMM%, %ADD…C/R/O/RoundRect…%
 * apertures (RoundRect: corner radius and the four corner centres), X…Y…D03 flashes and G36…G37 regions. Enough for
 * KiCad's own output, not a general Gerber reader. Coordinates are Gerber's: millimetres, Y up (= −KiCad Y).
 */
final class GerberApertures {
    /** One opening: centre, the two sides and the direction of the first side (degrees), the corner radius. */
    static final class Shape {
        String ref;          // the %TO.C component attribute, for messages
        String kind;         // "C", "R", "O", "RoundRect", "region"
        double x;
        double y;
        double a;            // first side
        double b;            // second side
        double angle;        // direction of side a
        double radius;
    }

    final List<Shape> flashes = new ArrayList<>();
    final List<Shape> regions = new ArrayList<>();

    private static final Pattern COORD = Pattern.compile("^(?:G0?1)?(?:X(-?\\d+))?(?:Y(-?\\d+))?D0?([123])$");

    private final Map<String, String[]> apertures = new HashMap<>();
    private int decimals = 6;
    private double unit = 1;
    private String current;
    private String ref;
    private double cx;
    private double cy;
    private List<double[]> contour;

    private GerberApertures() {
    }

    static GerberApertures read(String text) {
        GerberApertures g = new GerberApertures();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            }
            else if (c == '%') {
                int end = text.indexOf('%', i + 1);
                for (String cmd : text.substring(i + 1, end).split("\\*")) {
                    if (!cmd.trim().isEmpty()) {
                        g.extended(cmd.trim());
                    }
                }
                i = end + 1;
            }
            else {
                int end = text.indexOf('*', i);
                g.command(text.substring(i, end).trim());
                i = end + 1;
            }
        }
        return g;
    }

    private void extended(String cmd) {
        if (cmd.startsWith("FS")) {
            Matcher m = Pattern.compile("FSLAX(\\d)(\\d)Y(\\d)(\\d)").matcher(cmd);
            if (m.find()) {
                decimals = Integer.parseInt(m.group(2));
            }
        }
        else if (cmd.startsWith("MO")) {
            unit = cmd.equals("MOIN") ? 25.4 : 1;
        }
        else if (cmd.startsWith("ADD")) {
            Matcher m = Pattern.compile("ADD(\\d+)([A-Za-z_]+),?(.*)").matcher(cmd);
            if (m.matches()) {
                apertures.put(m.group(1), new String[] {m.group(2), m.group(3)});
            }
        }
        else if (cmd.startsWith("TO.C,")) {
            ref = cmd.substring(5);
        }
        else if (cmd.equals("TD")) {
            ref = null;
        }
    }

    private void command(String cmd) {
        if (cmd.startsWith("G04") || cmd.isEmpty() || cmd.equals("M02")) {
            return;
        }
        if (cmd.equals("G36")) {
            contour = new ArrayList<>();
            return;
        }
        if (cmd.equals("G37")) {
            regions.add(region(contour));
            contour = null;
            return;
        }
        if (cmd.matches("^(G54)?D\\d{2,}$")) {
            current = cmd.replaceAll("^(G54)?D", "");
            return;
        }
        Matcher m = COORD.matcher(cmd);
        if (!m.matches()) {
            return; // G01, G75, … : modes this reader does not need
        }
        if (m.group(1) != null) {
            cx = Long.parseLong(m.group(1)) / Math.pow(10, decimals) * unit;
        }
        if (m.group(2) != null) {
            cy = Long.parseLong(m.group(2)) / Math.pow(10, decimals) * unit;
        }
        int d = Integer.parseInt(m.group(3));
        if (contour != null) {
            contour.add(new double[] {cx, cy});
        }
        else if (d == 3) {
            flashes.add(flash());
        }
    }

    private Shape flash() {
        String[] ap = apertures.get(current);
        String[] p = ap[1].isEmpty() ? new String[0] : ap[1].split("X");
        Shape s = new Shape();
        s.ref = ref;
        s.kind = ap[0];
        s.x = cx;
        s.y = cy;
        switch (ap[0]) {
            case "C":
                s.a = s.b = Double.parseDouble(p[0]) * unit;
                s.radius = s.a / 2;
                break;
            case "R":
                s.a = Double.parseDouble(p[0]) * unit;
                s.b = Double.parseDouble(p[1]) * unit;
                break;
            case "O":
                s.a = Double.parseDouble(p[0]) * unit;
                s.b = Double.parseDouble(p[1]) * unit;
                s.radius = Math.min(s.a, s.b) / 2;
                break;
            case "RoundRect": {
                double r = Double.parseDouble(p[0]) * unit;
                double[] q = new double[8];
                for (int k = 0; k < 8; k++) {
                    q[k] = Double.parseDouble(p[k + 1]) * unit;
                }
                s.radius = r;
                s.a = Math.hypot(q[2] - q[0], q[3] - q[1]) + 2 * r;
                s.b = Math.hypot(q[4] - q[2], q[5] - q[3]) + 2 * r;
                s.angle = Math.toDegrees(Math.atan2(q[3] - q[1], q[2] - q[0]));
                s.x += (q[0] + q[2] + q[4] + q[6]) / 4;
                s.y += (q[1] + q[3] + q[5] + q[7]) / 4;
                break;
            }
            default:
                throw new IllegalStateException("aperture " + ap[0] + " not supported");
        }
        return s;
    }

    /** A four-corner region (repeated closing point allowed) as a rectangle. */
    private Shape region(List<double[]> pts) {
        if (pts.size() == 5 && Math.hypot(pts.get(4)[0] - pts.get(0)[0], pts.get(4)[1] - pts.get(0)[1]) < 1e-9) {
            pts = pts.subList(0, 4);
        }
        if (pts.size() != 4) {
            throw new IllegalStateException("region with " + pts.size() + " corners");
        }
        Shape s = new Shape();
        s.ref = ref;
        s.kind = "region";
        for (double[] p : pts) {
            s.x += p[0] / 4;
            s.y += p[1] / 4;
        }
        s.a = Math.hypot(pts.get(1)[0] - pts.get(0)[0], pts.get(1)[1] - pts.get(0)[1]);
        s.b = Math.hypot(pts.get(2)[0] - pts.get(1)[0], pts.get(2)[1] - pts.get(1)[1]);
        s.angle = Math.toDegrees(Math.atan2(pts.get(1)[1] - pts.get(0)[1], pts.get(1)[0] - pts.get(0)[0]));
        return s;
    }
}
