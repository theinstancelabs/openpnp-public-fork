package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * Offline bench tool: runs {@link PadCleanCheck} on saved camera frames and prints one JSON object
 * per file and line. Touches no camera or machine.
 *
 * <pre>
 * java -cp openpnp-gui.jar:lib/* org.openpnp.instancelabs.vision.PadCleanTool \
 *     --scale 0.01074,0.01106 before_*.jpg
 * </pre>
 */
public final class PadCleanTool {
    static final String USAGE = String.join("\n",
            "usage: PadCleanTool --scale SX[,SY] IMAGE... [options]",
            "  --scale SX[,SY]         mm per pixel (required)",
            "  --offset DX,DY          box centre in mm from the image centre, +y up (default 0,0)",
            "  --gray-level G          bright = grey > G, 0-254 (default 200)",
            "  --min-fraction F        clean = bright fraction >= F (default 0.8)",
            "  --box W,H               box size in mm (default 0.806,0.807)",
            "prints {file, brightFraction, clean, x0, y0, w, h, brightPx, totalPx} per line;",
            "exit 1 if any file failed ({file, error})");

    private PadCleanTool() {}

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Runs the tool; returns the process exit code (0 ok, 1 runtime error, 2 usage error). */
    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> files = new ArrayList<>();
        PadCleanCheck.Settings s = new PadCleanCheck.Settings();
        double[] scale = null;
        double[] offset = {0, 0};
        try {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h":
                    case "--help":
                        out.println(USAGE);
                        return 0;
                    case "--scale":
                        scale = pair(value(args, ++i, a), a, true);
                        break;
                    case "--offset":
                        offset = pair(value(args, ++i, a), a, false);
                        break;
                    case "--gray-level":
                        s.grayLevel = Integer.parseInt(value(args, ++i, a));
                        break;
                    case "--min-fraction":
                        s.minBrightFraction = Double.parseDouble(value(args, ++i, a));
                        break;
                    case "--box": {
                        double[] b = pair(value(args, ++i, a), a, false);
                        s.boxWMm = b[0];
                        s.boxHMm = b[1];
                        break;
                    }
                    default:
                        if (a.startsWith("--")) {
                            throw new IllegalArgumentException("unknown option " + a);
                        }
                        files.add(a);
                }
            }
            if (scale == null) {
                throw new IllegalArgumentException("--scale is required");
            }
            if (!(scale[0] > 0) || !(scale[1] > 0)) {
                throw new IllegalArgumentException("mm per pixel must be positive");
            }
            if (files.isEmpty()) {
                throw new IllegalArgumentException("expected at least one IMAGE");
            }
            s.validate();
        }
        catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            err.println(USAGE);
            return 2;
        }

        Gson gson = new Gson();
        int code = 0;
        for (String f : files) {
            JsonObject o = new JsonObject();
            o.addProperty("file", f);
            try {
                BufferedImage img = ImageIO.read(new File(f));
                if (img == null) {
                    throw new IllegalArgumentException("cannot read image " + f);
                }
                PadCleanCheck.Result r =
                        PadCleanCheck.measure(img, scale[0], scale[1], offset[0], offset[1], s);
                o.addProperty("brightFraction", r.brightFraction);
                o.addProperty("clean", r.clean);
                o.addProperty("x0", r.x0);
                o.addProperty("y0", r.y0);
                o.addProperty("w", r.boxWPx);
                o.addProperty("h", r.boxHPx);
                o.addProperty("brightPx", r.brightPx);
                o.addProperty("totalPx", r.totalPx);
            }
            catch (Exception e) {
                o.addProperty("error", e.toString());
                code = 1;
            }
            out.println(gson.toJson(o));
        }
        return code;
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[i];
    }

    /** "A,B" (or just "A" when single is allowed, meaning "A,A"). */
    private static double[] pair(String v, String option, boolean single) {
        String[] f = v.split(",");
        if (f.length != 2 && !(single && f.length == 1)) {
            throw new IllegalArgumentException(option + " expects " + (single ? "A[,B]" : "A,B"));
        }
        double a = Double.parseDouble(f[0].trim());
        return new double[] {a, f.length > 1 ? Double.parseDouble(f[1].trim()) : a};
    }
}
