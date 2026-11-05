package org.openpnp.instancelabs.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * BR-94: is the needle's end clean enough to press the height switch? Process's tipclean.py (runs/p37/tipclean.md,
 * 11-05), ported: one frame at the end measure's pose, the ring light full white, the end = np1.py's top-hat spot within
 * 2 mm of the camera's pixel. It passes when every check passes, in this order: light (the ring's glow at the frame's
 * left and right edges), found (the top-hat's peak), z (the scale k, i.e. the height), spread (top-hat light in the
 * 0.5-1.0 mm annulus over the light inside 0.5 mm: paste reflects the ring off its wet surface, partly outside the end)
 * and red (sum R / sum G inside 0.35 mm: a clean red 25G end glows its own colour, paste the ring's).
 */
public final class TipClean {
    /** tipclean.py: the ring's glow is read in rows 340-739 at the frame's 140 px left and right edges (1920 x 1080). */
    static final int RIM_TOP = 340, RIM_BOTTOM = 740, RIM_W = 140, FRAME_W = 1920, FRAME_H = 1080;

    private TipClean() {
    }

    /**
     * The checks' limits for one tip kind; {@code new Limits()} is Process's 25G plastic taper (tipclean.md). A kind's
     * entry in the settings gives every limit itself ({@link #of}): never the 25G's for one it leaves out.
     */
    public static class Limits {
        private static final Gson GSON = new Gson();
        private static final java.lang.reflect.Type ENTRY = new TypeToken<Map<String, Double>>() {
        }.getType();

        /** light: the rim's grey at least, its R/G within (the white ring on: 0.27-0.34 on record). */
        public double rimMin = 60, rimRgLow = 0.20, rimRgHigh = 0.45;
        /** found: the top-hat's peak at least (clean 25G 98-149; the needle at travel Z: 4-24). */
        public double peakMin = 60;
        /** z: k within (px/mm; raw Z 36-42, where 25G frames are on record). */
        public double kLow = 14.0, kHigh = 16.3;
        /** spread at most (clean 0.16-0.26; paste 0.47-0.65): the geometric mean of the margins. */
        public double spreadMax = 0.35;
        /** red at least (the red plastic's own glow 0.61-1.02; the ring's light off wet paste 0.14-0.34). */
        public double redMin = 0.45;
        /** The radii (mm): the end (a blurred clean end is ~0.8 across), the annulus (paste rims and tails), red, the search. */
        public double endMm = 0.5, annulusMm = 1.0, redMm = 0.35, findMm = 2.0;

        /** These limits as a settings entry: {name: value}, every limit. */
        public Map<String, Double> toMap() {
            return GSON.fromJson(GSON.toJson(this), ENTRY);
        }

        /** A settings entry's limits, each its own; null when it is no kind's limits ({@link #problem}). */
        public static Limits of(Map<String, Double> entry) {
            return problem(entry) != null ? null : GSON.fromJson(GSON.toJson(entry), Limits.class);
        }

        /** Why a settings entry is no kind's limits (a limit missing or no number, a key that is no limit), or null. */
        public static String problem(Map<String, Double> entry) {
            if (entry == null) {
                return "no limits";
            }
            Set<String> names = new Limits().toMap().keySet();
            List<String> missing = new ArrayList<>(), unknown = new ArrayList<>();
            for (String n : names) {
                Double v = entry.get(n);
                if (v == null || v.isNaN() || v.isInfinite()) {
                    missing.add(n);
                }
            }
            for (String k : entry.keySet()) {
                if (!names.contains(k)) {
                    unknown.add("'" + k + "'");
                }
            }
            return missing.isEmpty() && unknown.isEmpty() ? null
                    : (missing.isEmpty() ? "" : "no " + String.join(", ", missing) + " (every limit is the kind's own)")
                            + (missing.isEmpty() || unknown.isEmpty() ? "" : "; ") + (unknown.isEmpty() ? ""
                                    : String.join(", ", unknown) + " is no limit (" + String.join(", ", names) + ")");
        }
    }

    /** What the check read, and the first check that failed (null: clean). */
    public static final class Result {
        public double endX, endY, k, rim, rimRg, peak, spread, red;
        /** light | found | z | spread | red, or null. */
        public String failed;
        public String why;

        public boolean ok() {
            return failed == null;
        }
    }

    /**
     * tipclean.py's check(): {@code img} the frame (1920 x 1080), {@code k} its scale at the end (px/mm), (bcx, bcy)
     * the pixel of the camera's location, {@code kernelPx} np1.py's top-hat.
     */
    public static Result check(BufferedImage img, double k, double bcx, double bcy, int kernelPx, Limits l) {
        Result r = new Result();
        r.k = k;
        int w = img.getWidth(), h = img.getHeight();
        if (w != FRAME_W || h != FRAME_H) {
            r.failed = "light";
            r.why = String.format(Locale.ROOT, "light: the frame is %d x %d px; the check reads the ring's glow in a "
                    + "%d x %d frame", w, h, FRAME_W, FRAME_H);
            return r;
        }
        double sb = 0, sg = 0, sr = 0, n = 0;
        for (int y = RIM_TOP; y < RIM_BOTTOM; y++) {
            for (int x = 0; x < w; x = x == RIM_W - 1 ? w - RIM_W : x + 1) {
                int c = img.getRGB(x, y);
                sr += (c >> 16) & 0xff;
                sg += (c >> 8) & 0xff;
                sb += c & 0xff;
                n++;
            }
        }
        r.rim = (sb + sg + sr) / 3 / n;
        r.rimRg = (sr / n) / Math.max(sg / n, 1);
        byte[] th = EndFromBelow.topHat(img, kernelPx);
        EndFromBelow.Spot s = EndFromBelow.spot(th, w, h, bcx, bcy, (int) (l.findMm * k), kernelPx);
        r.endX = s.x;
        r.endY = s.y;
        r.peak = s.peak;
        double end = 0, ring = 0, red = 0, green = 0;
        int reach = (int) Math.ceil(Math.max(l.annulusMm, l.redMm) * k) + 1;
        for (int y = Math.max(0, (int) s.y - reach); y <= Math.min(h - 1, (int) s.y + reach); y++) {
            for (int x = Math.max(0, (int) s.x - reach); x <= Math.min(w - 1, (int) s.x + reach); x++) {
                double d = Math.hypot(x - s.x, y - s.y) / k;
                int t = th[y * w + x] & 0xff;
                if (d <= l.endMm) {
                    end += t;
                }
                else if (d <= l.annulusMm) {
                    ring += t;
                }
                if (d <= l.redMm) {
                    int c = img.getRGB(x, y);
                    red += (c >> 16) & 0xff;
                    green += (c >> 8) & 0xff;
                }
            }
        }
        r.spread = ring / Math.max(end, 1);
        r.red = red / Math.max(green, 1);
        if (r.rim < l.rimMin || r.rimRg < l.rimRgLow || r.rimRg > l.rimRgHigh) {
            r.failed = "light";
            r.why = String.format(Locale.ROOT, "light: the ring is off or not white (its glow at the frame's edges: grey "
                    + "%.1f, at least %.0f; R/G %.3f, %.2f-%.2f)", r.rim, l.rimMin, r.rimRg, l.rimRgLow, l.rimRgHigh);
        }
        else if (r.peak < l.peakMin) {
            r.failed = "found";
            r.why = String.format(Locale.ROOT, "found: no end highlight within %.1f mm of the camera's centre (top-hat "
                    + "peak %.0f, at least %.0f): the pose, the height or the light, or the end is buried", l.findMm,
                    r.peak, l.peakMin);
        }
        else if (k < l.kLow || k > l.kHigh) {
            r.failed = "z";
            r.why = String.format(Locale.ROOT, "z: k %.2f px/mm, outside %.1f-%.1f (the heights on record): the frame "
                    + "is too far from the end measure's pose", k, l.kLow, l.kHigh);
        }
        else if (r.spread > l.spreadMax) {
            r.failed = "spread";
            r.why = String.format(Locale.ROOT, "spread: light beyond the end's %.1f mm, %.3f of the end's (at most "
                    + "%.2f): paste on the end, or out of focus", l.endMm, r.spread, l.spreadMax);
        }
        else if (r.red < l.redMin) {
            r.failed = "red";
            r.why = String.format(Locale.ROOT, "red: the end glows the ring's colour, not the tip's (R/G %.3f inside "
                    + "%.2f mm, at least %.2f): paste over the exit", r.red, l.redMm, l.redMin);
        }
        return r;
    }
}
