package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BR-76: the dots in the lines sent to the controller, the engine's and the passthrough's alike (Process 11-04: "written
 * at dispense time, never by a later scan"). A dot is a forward screw turn while the needle's valve is open (M106 P3),
 * where the tip is then: the raw X/Y/Z the lines named last, else OpenPnP's. Turns at one spot are one dot until X, Y
 * or Z moves; a turn while they move lays a line of paste, given as its path every {@link #STEP_MM}.
 */
public final class DotWatch {
    /** A line of paste is sampled this often (a QFN row: every pad under it). */
    static final double STEP_MM = 0.2;
    private static final Pattern P = Pattern.compile("\\bP\\s*(\\d+)"), S = Pattern.compile("\\bS\\s*([0-9.]+)");
    private final char x, y, z, b;
    private final int sign;
    private final double minTurn;
    /** The axes' targets the lines named since OpenPnP last set them. */
    private final Map<Character, Double> at = new HashMap<>();
    private boolean valve;
    /** The last turn laid a dot where the tip still is: a turn there continues it. */
    private boolean onDot;

    /**
     * The controller's letters of the needle's X, Y, Z and of the screw; {@code extrudeSign} +1 when a rising screw
     * coordinate extrudes; {@code minTurn} the least turn that moves the screw (half a step, in its units).
     */
    public DotWatch(char x, char y, char z, char b, int extrudeSign, double minTurn) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.b = b;
        this.sign = extrudeSign;
        this.minTurn = minTurn;
    }

    /** OpenPnP set the axes (it moved the head, a STOP, a connect): its coordinates hold again. */
    public synchronized void forget() {
        at.clear();
        onDot = false;
    }

    /** The valve was closed outside these lines (a STOP's DISABLE_COMMAND). */
    public synchronized void valveClosed() {
        valve = false;
        onDot = false;
    }

    /**
     * One line sent: the raw {x, y, z} points of the new dot it lays, else an empty list. {@code now} gives OpenPnP's
     * coordinate of an axis the lines have not named (null: unknown, no dot).
     */
    public synchronized List<double[]> line(String line, Function<Character, Double> now) {
        List<double[]> out = new ArrayList<>();
        String s = ControllerCommands.normalize(line);
        String w = ControllerCommands.word(s);
        if ("M106".equals(w) || "M107".equals(w)) {
            Matcher p = P.matcher(s), v = S.matcher(s);
            if (p.find() && Integer.parseInt(p.group(1)) == 3) {
                valve = "M106".equals(w) && (!v.find() || Double.parseDouble(v.group(1)) > 0);
            }
            return out;
        }
        if ("G28".equals(w)) {
            forget();
            return out;
        }
        if ("G92".equals(w)) {
            at.putAll(ControllerCommands.axisWords(line));
            return out;
        }
        if (!"G0".equals(w) && !"G1".equals(w)) {
            return out;
        }
        Map<Character, Double> words = ControllerCommands.axisWords(line);
        double[] from = where(now);
        Double b0 = axis(b, now);
        at.putAll(words);
        double[] to = where(now);
        boolean moved = Math.abs(to[0] - from[0]) > 1e-4 || Math.abs(to[1] - from[1]) > 1e-4
                || Math.abs(to[2] - from[2]) > 1e-4;
        Double b1 = words.get(b);
        boolean turn = valve && b0 != null && b1 != null && (b1 - b0) * sign >= minTurn;
        if (turn && (moved || !onDot) && !Double.isNaN(from[0] + from[1] + from[2] + to[0] + to[1] + to[2])) {
            int n = (int) Math.ceil(Math.hypot(to[0] - from[0], to[1] - from[1]) / STEP_MM);
            n = moved ? Math.max(1, n) : 0;
            for (int i = 0; i <= n; i++) {
                double t = n == 0 ? 1 : (double) i / n;
                out.add(new double[] {from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t,
                        from[2] + (to[2] - from[2]) * t});
            }
        }
        onDot = turn || !moved && onDot;
        return out;
    }

    private double[] where(Function<Character, Double> now) {
        Double px = axis(x, now), py = axis(y, now), pz = axis(z, now);
        return new double[] {px == null ? Double.NaN : px, py == null ? Double.NaN : py, pz == null ? Double.NaN : pz};
    }

    private Double axis(char a, Function<Character, Double> now) {
        Double v = at.get(a);
        return v != null ? v : now.apply(a);
    }
}
