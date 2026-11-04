package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BR-03: where the valve and the screw may switch on, in machine X/Y where the needle's paste lands. Allowed, option (b)
 * of the process engineer's 11-04 decision: the places and surfaces the engine's run settings name as where paste goes.
 * The sink places (the waste by {@code wasteLocationName}, the towel, the cup: a circle of {@code placeRadiusMm}), each
 * coupon's rectangle, the purge row and the calibration area (each with {@code marginMm}), and the probed board and
 * blank surfaces ({@code boardSurfaceName}: plan runs; {@code blankLocationName}: the dot ladder and the touch-off) by
 * I4's own rule ({@link Interlocks#onSurface}: within {@code surfaceMarginMm} of its points), so I4 and I12 never
 * disagree about a dot. Every other place (the park, feeder picks, camera places, the oven) and every other probed
 * surface is outside; a refusal there names it. Forbidden, whatever else covers it: the bottom camera (a circle of
 * {@code forbidRadiusMm}) and the configured circles. With the valve on the needle never crosses a forbidden zone.
 * The pressure hold, which may drool for minutes, opens the valve only over a sink: the waste, the towel, the cup, the
 * purge row, the calibration area or a coupon (never over a board or another surface: #12's final review).
 * The refusals say what to do in their context: the hold where the needle is ({@link #refusal}), its park
 * ({@link #parkRefusal}), a program's line ({@link #lineRefusal}) and move ({@link #crossing}).
 */
public final class OutputZones {
    /** A circle, a box or a probed surface, in machine X/Y (mm). */
    public static final class Zone {
        /** The status's name ("waste", "surface board"); {@code label} names it in a text ("the place 'waste'"). */
        public final String name, label;
        public final boolean circle;
        public final double a, b, c, d; // circle: cx, cy, r; box: x0, y0, x1, y1
        /** Takes the pressure hold's drool: the waste, the towel, the cup, the purge row, the area, a coupon. */
        public final boolean sink;
        /** A probed surface's plane and the configuration whose I4 margin applies; null for a circle or a box. */
        private final SurfacePlane plane;
        private final DispenseConfig cfg;

        private Zone(String name, String label, boolean sink, boolean circle, double a, double b, double c, double d,
                SurfacePlane plane, DispenseConfig cfg) {
            this.name = name;
            this.label = label;
            this.sink = sink;
            this.circle = circle;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.plane = plane;
            this.cfg = cfg;
        }

        static Zone circle(String name, String label, boolean sink, double cx, double cy, double r) {
            return new Zone(name, label, sink, true, cx, cy, r, 0, null, null);
        }

        /** The purge row, the calibration area, a coupon: sinks. */
        static Zone box(String name, double x0, double y0, double x1, double y1, double margin) {
            return new Zone(name, "the " + name, true, false, Math.min(x0, x1) - margin, Math.min(y0, y1) - margin,
                    Math.max(x0, x1) + margin, Math.max(y0, y1) + margin, null, null);
        }

        static Zone surface(String surface, SurfacePlane plane, DispenseConfig cfg) {
            return new Zone("surface " + surface, "the probed '" + surface + "' surface", false, false, 0, 0, 0, 0,
                    plane, cfg);
        }

        public boolean contains(double x, double y) {
            if (plane != null) {
                return Interlocks.onSurface(cfg, plane, x, y);
            }
            if (circle) {
                return Math.hypot(x - a, y - b) <= c;
            }
            return x >= a && x <= c && y >= b && y <= d;
        }

        /** How far (x, y) lies beyond what the zone allows (mm); 0 inside. */
        double beyond(double x, double y) {
            if (plane != null) {
                return Math.max(0, plane.distanceOutside(x, y) - cfg.surfaceMarginMm);
            }
            if (circle) {
                return Math.max(0, Math.hypot(x - a, y - b) - c);
            }
            return Math.hypot(Math.max(0, Math.max(a - x, x - c)), Math.max(0, Math.max(b - y, y - d)));
        }

        /** Where (x, y) lies against this allowed zone, and what to do: a surface by I4's numbers. */
        String outside(double x, double y) {
            if (plane != null) {
                return String.format(Locale.ROOT, "%.1f mm beyond %s's points (margin %.1f mm): probe around it",
                        plane.distanceOutside(x, y), label, cfg.surfaceMarginMm);
            }
            return String.format(Locale.ROOT, "%.1f mm outside %s: set it again where the paste goes", beyond(x, y),
                    label);
        }

        /** The segment comes within the circle (forbidden zones are circles). */
        boolean touchedBy(double x0, double y0, double x1, double y1) {
            double dx = x1 - x0, dy = y1 - y0, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((a - x0) * dx + (b - y0) * dy) / len2));
            return Math.hypot(x0 + t * dx - a, y0 + t * dy - b) <= c;
        }
    }

    public static final String BOTTOM_CAMERA = "bottom camera";

    private final List<Zone> allow = new ArrayList<>();
    private final List<Zone> forbid = new ArrayList<>();
    /** The places and probed surfaces no run puts paste on: never allowed, only named in a program's refusal. */
    private final List<Zone> other = new ArrayList<>();
    /** Where paste may go, in words, with the settings' surface names. */
    private String where;

    private OutputZones() {
    }

    /**
     * @param bottomCameraXY the bottom camera's X/Y (mm), or null when the machine has none
     */
    public static OutputZones of(DispenseConfig c, double[] bottomCameraXY) {
        OutputZones z = new OutputZones();
        DispenseConfig.OutputZoneSettings s = c.outputZones != null ? c.outputZones
                : new DispenseConfig.OutputZoneSettings();
        if (bottomCameraXY != null) {
            z.forbid.add(Zone.circle(BOTTOM_CAMERA, "the " + BOTTOM_CAMERA, false, bottomCameraXY[0],
                    bottomCameraXY[1], s.forbidRadiusMm));
        }
        if (s.forbid != null) {
            for (DispenseConfig.ForbidZone f : s.forbid) {
                if (f != null) {
                    String name = f.name == null ? "forbidden circle" : f.name;
                    z.forbid.add(Zone.circle(name, "the " + name, false, f.x, f.y, f.radiusMm));
                }
            }
        }
        for (Map.Entry<String, DispenseConfig.NamedLocation> e : c.locations.entrySet()) {
            if (e.getValue() != null) {
                boolean sink = isSinkPlace(c, e.getKey());
                (sink ? z.allow : z.other).add(Zone.circle(e.getKey(), "the place '" + e.getKey() + "'", sink,
                        e.getValue().x, e.getValue().y, s.placeRadiusMm));
            }
        }
        if (c.padCalibration != null && c.padCalibration.couponPlaces != null) {
            for (String place : c.padCalibration.couponPlaces) {
                double[] r = place == null ? null : c.padCalibration.couponRect(c, place);
                if (r != null) {
                    z.allow.add(Zone.box("coupon " + place, r[0], r[1], r[2], r[3], s.marginMm));
                }
            }
        }
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (row != null && row.isDefined()) {
            double[] first = row.spot(0), last = row.spot(row.spots - 1);
            z.allow.add(Zone.box("purge row", first[0], first[1], last[0], last[1], s.marginMm));
        }
        CalibrationArea area = c.calibrationArea;
        if (area != null && area.isDefined()) {
            z.allow.add(Zone.box("calibration area", area.x0Mm, area.y0Mm, area.x0Mm + area.widthMm,
                    area.y0Mm + area.heightMm, s.marginMm));
        }
        for (Map.Entry<String, DispenseConfig.SurfaceMap> e : c.surfaces.entrySet()) {
            DispenseConfig.SurfaceMap m = e.getValue();
            if (m == null) {
                continue;
            }
            // plan runs dispense on the board surface, the dot ladder and the touch-off on the blank
            boolean used = e.getKey().equals(c.boardSurfaceName) || e.getKey().equals(c.blankLocationName);
            try {
                (used ? z.allow : z.other).add(Zone.surface(e.getKey(), SurfacePlane.fit(m.points), c));
            }
            catch (IllegalArgumentException notAPlane) {
                // fewer than 3 points or all on one line: I4 refuses every dot on it too
            }
        }
        z.where = "the waste, the towel, the cup, the purge row, the calibration area, a coupon, or the probed '"
                + c.boardSurfaceName + "' or '" + c.blankLocationName + "' surface";
        return z;
    }

    /**
     * The places that take the hold's drool: the waste (by its setting), the towel, and a cup: any place named like a
     * cup or a scale, as the scale shots' picker offers them (calibrate.js).
     */
    static boolean isSinkPlace(DispenseConfig c, String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return name.equalsIgnoreCase(c.wasteLocationName) || n.equals("towel") || n.contains("cup")
                || n.contains("scale");
    }

    /** "forbidden: <name>", the name of the allowed zone the point is in, or null when it is in none. */
    public String zoneAt(double x, double y) {
        Zone f = forbiddenAt(x, y);
        if (f != null) {
            return "forbidden: " + f.name;
        }
        Zone a = allowedAt(x, y);
        return a == null ? null : a.name;
    }

    /**
     * The hold where the needle is: null when the valve may open with its paste landing at (x, y); else "the needle
     * (x, y) is …: park first (or start it with park: 'waste')".
     */
    public String refusal(double x, double y) {
        String why = whyHold(x, y);
        return why == null ? null : String.format(Locale.ROOT, "the needle (%.1f, %.1f) is %s: park first (or start "
                + "it with park: 'waste')", x, y, why);
    }

    /** The hold's park over {@code place} (null: over a point) at (x, y): null when allowed; else why not. */
    public String parkRefusal(String place, double x, double y) {
        String why = whyHold(x, y);
        return why == null ? null : String.format(Locale.ROOT, "the park over %s(%.1f, %.1f) is %s: park over another "
                + "place", place == null ? "" : "'" + place + "' ", x, y, why);
    }

    /**
     * A program's line that switches the valve on or turns the screw ({@code what}: "the valve ON at line 9", "dot
     * 'R16.1' at line 57"), its paste landing at (x, y): null when allowed; else where the paste would go and what to
     * do (never "park": a program does not park).
     */
    public String lineRefusal(String what, double x, double y) {
        String at = String.format(Locale.ROOT, "%s puts paste at (%.1f, %.1f), ", what, x, y);
        Zone f = forbiddenAt(x, y);
        if (f != null) {
            return at + String.format(Locale.ROOT, "over %s, where the valve and the screw stay off: set that place "
                    + "or surface %.0f mm or more away from it", f.label, f.c);
        }
        return allowedAt(x, y) != null ? null : at + why(x, y);
    }

    /**
     * Null when a straight move with the valve on never crosses a forbidden zone; else "moves the needle from … to …
     * with the valve on, across …" and what to do.
     */
    public String crossing(double x0, double y0, double x1, double y1) {
        for (Zone f : forbid) {
            if (f.touchedBy(x0, y0, x1, y1)) {
                return String.format(Locale.ROOT, "moves the needle from (%.1f, %.1f) to (%.1f, %.1f) with the valve "
                        + "on, across %s (%.0f mm around it): set the places or boards at its ends so that the path "
                        + "between them misses it", x0, y0, x1, y1, f.label, f.c);
            }
        }
        return null;
    }

    /**
     * Why paste at (x, y), in no allowed zone, may not go there and what to do: over a place or surface no run puts
     * paste on, where paste goes instead; else how far it lies beyond the nearest allowed zone.
     */
    private String why(double x, double y) {
        for (Zone o : other) {
            if (o.contains(x, y)) {
                return "over " + o.label + ", which takes no paste: paste goes only on " + where;
            }
        }
        Zone near = null;
        for (Zone a : allow) {
            if (near == null || a.beyond(x, y) < near.beyond(x, y)) {
                near = a;
            }
        }
        return near == null ? "and no place where paste may go is set (" + where + "): set the place or probe the "
                + "surface it dispenses on" : near.outside(x, y);
    }

    /** Why the pressure hold may not open the valve at (x, y), null when it may: only a sink takes its drool. */
    private String whyHold(double x, double y) {
        Zone f = forbiddenAt(x, y);
        if (f != null) {
            return "over " + f.label + ", where the valve and the screw stay off";
        }
        for (Zone a : allow) {
            if (a.sink && a.contains(x, y)) {
                return null;
            }
        }
        Zone a = allowedAt(x, y);
        return (a != null ? "over " + a.label + ", " : "") + "not over a place that takes paste while the pressure is "
                + "held (the waste, the towel, the cup, the purge row, the calibration area or a coupon)";
    }

    private Zone forbiddenAt(double x, double y) {
        for (Zone f : forbid) {
            if (f.contains(x, y)) {
                return f;
            }
        }
        return null;
    }

    private Zone allowedAt(double x, double y) {
        for (Zone a : allow) {
            if (a.contains(x, y)) {
                return a;
            }
        }
        return null;
    }

    public List<Zone> allowed() {
        return new ArrayList<>(allow);
    }

    public List<Zone> forbidden() {
        return new ArrayList<>(forbid);
    }
}
