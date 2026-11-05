package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * BR-03: where the valve and the screw may switch on, in machine X/Y where the needle's paste lands. Allowed, option (b)
 * of the process engineer's 11-04 decision: the places and surfaces the engine's run settings name as where paste goes.
 * The sink places (the waste by {@code wasteLocationName}, the test dot's spot on it, the towel, a cup: a circle of
 * {@code placeRadiusMm}), each coupon's rectangle, the purge row and the calibration area (the default one at the blank
 * when unset; each with {@code marginMm}), and the probed board and blank surfaces ({@code boardSurfaceName}: plan
 * runs; {@code blankLocationName}: the dot ladder and the touch-off) by I4's own rule ({@link Interlocks#onSurface}:
 * within {@code surfaceMarginMm} of its points), so I4 and I12 never disagree about a dot; a program is also allowed
 * the surfaces its own I4 scope names (a dot ladder at another place lays its dots on that place's surface). Every
 * other place (the park, feeder picks, camera places, the oven) and every other probed surface is outside; a refusal
 * there names it. Forbidden, whatever else covers it: the bottom camera (a circle of {@code forbidRadiusMm}), the
 * height switch (BR-94: a circle of {@code heightSwitchRadiusMm} around its place, once set) and the configured
 * circles. With the valve on the needle never crosses a forbidden zone. The pressure hold, which may drool for minutes,
 * opens the valve only over a sink: the waste, the towel, a cup, the purge row, the calibration area or a
 * coupon (never over a board or another surface: #12's final review). BR-81: within {@code marginMm} of a board on
 * the bed ({@link BoardOutlines}: a registration's, an imported tile) paste goes only as a board run's planned dots,
 * and only on the outlines recorded up to its registration's last check ({@link Interlocks.Scope#outlinesUpToMs}: a
 * newer outline means the bed changed; a plan without a registration has none), whatever place covers it. The hold
 * keeps its drool's circle ({@code placeRadiusMm}) and the margin off every board, its park too. The refusals say what
 * to do in their context: the hold where the needle is ({@link #refusal}), its park ({@link #parkRefusal}), a
 * program's line ({@link #lineRefusal}) and move ({@link #crossing}).
 */
public final class OutputZones {
    /** A circle, a box or a probed surface, in machine X/Y (mm). */
    public static final class Zone {
        /** The status's name ("waste", "surface board"); {@code label} names it in a text ("the place 'waste'"). */
        public final String name, label;
        public final boolean circle;
        public final double a, b, c, d; // circle: cx, cy, r; box: x0, y0, x1, y1
        /** Takes the pressure hold's drool: the waste, the towel, a cup, the purge row, the area, a coupon. */
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
    public static final String HEIGHT_SWITCH = "height switch";

    private final List<Zone> allow = new ArrayList<>();
    private final List<Zone> forbid = new ArrayList<>();
    /** The places and probed surfaces no run puts paste on: never allowed, only named in a program's refusal. */
    private final List<Zone> other = new ArrayList<>();
    /** Where paste may go, in words, with the settings' surface names. */
    private String where;
    /**
     * BR-81: the boards on the bed, the margin paste keeps off them and the hold's (its circle more); a board run's
     * planned dots and the time up to which outlines take them; the waste (the hold's suggested park).
     */
    private BoardOutlines boards;
    private double boardMarginMm, holdMarginMm;
    private final List<double[]> ownDots = new ArrayList<>();
    private Long ownUpToMs;
    private DispenseConfig.NamedLocation waste;
    /** A board run's own dot: within this of a planned target (mm; the G-code's rounding is far below it). */
    static final double OWN_DOT_MM = 0.5;

    private OutputZones() {
    }

    /**
     * The default set: where the needle may hold the pressure and what the status shows (no program).
     *
     * @param bottomCameraXY the bottom camera's X/Y (mm), or null when the machine has none
     */
    public static OutputZones of(DispenseConfig c, double[] bottomCameraXY) {
        return of(c, bottomCameraXY, null);
    }

    /**
     * The zones a program is validated against: the default set plus the surfaces its own I4 {@code scope} names
     * ({@code surfaceName}, {@code moreSurfaces}), by I4's rule, so I4 and I12 agree on a run's own surface (the dot
     * ladder and the touch-off take a {@code locationName}: their dots lie on that place's surface). Null: the default.
     */
    public static OutputZones of(DispenseConfig c, double[] bottomCameraXY, Interlocks.Scope scope) {
        OutputZones z = new OutputZones();
        DispenseConfig.OutputZoneSettings s = c.outputZones != null ? c.outputZones
                : new DispenseConfig.OutputZoneSettings();
        z.boards = BoardOutlines.of(c);
        z.boardMarginMm = s.marginMm;
        z.holdMarginMm = s.marginMm + s.placeRadiusMm;
        z.waste = c.location(c.wasteLocationName);
        if (scope != null && scope.checkPlanSurface) {
            z.ownDots.addAll(scope.surfaceTargets);
            z.ownUpToMs = scope.outlinesUpToMs;
        }
        if (bottomCameraXY != null) {
            z.forbid.add(Zone.circle(BOTTOM_CAMERA, "the " + BOTTOM_CAMERA, false, bottomCameraXY[0],
                    bottomCameraXY[1], s.forbidRadiusMm));
        }
        DispenseConfig.NamedLocation hs = c.location(HeightSwitch.PLACE);
        if (hs != null) {
            z.forbid.add(Zone.circle(HEIGHT_SWITCH, "the " + HEIGHT_SWITCH, false, hs.x, hs.y, s.heightSwitchRadiusMm));
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
            // the test dot's spot is taken from the waste below: its stored copy lags a moved waste until the next one
            if (e.getValue() != null && !e.getKey().equals(DispenseService.WASTE_TEST_DOT)) {
                boolean sink = isSinkPlace(c, e.getKey());
                (sink ? z.allow : z.other).add(Zone.circle(e.getKey(), "the place '" + e.getKey() + "'", sink,
                        e.getValue().x, e.getValue().y, s.placeRadiusMm));
            }
        }
        DispenseConfig.NamedLocation waste = c.location(c.wasteLocationName);
        if (waste != null) {
            z.allow.add(Zone.circle(DispenseService.WASTE_TEST_DOT, "the place '" + DispenseService.WASTE_TEST_DOT
                    + "'", true, waste.x + c.testDotOffsetXMm, waste.y + c.testDotOffsetYMm, s.placeRadiusMm));
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
        // unset, the pad calibration and the landing check use the default area at the blank
        CalibrationArea area = c.calibrationArea == null ? null : c.calibrationArea.effective(c);
        if (area != null) {
            z.allow.add(Zone.box("calibration area", area.x0Mm, area.y0Mm, area.x0Mm + area.widthMm,
                    area.y0Mm + area.heightMm, s.marginMm));
        }
        // plan runs dispense on the board surface, the dot ladder and the touch-off on the blank, a program on its own
        Set<String> used = new LinkedHashSet<>(Arrays.asList(c.boardSurfaceName, c.blankLocationName));
        if (scope != null) {
            if (scope.surfaceName != null) {
                used.add(scope.surfaceName);
            }
            used.addAll(scope.moreSurfaces.keySet());
        }
        for (Map.Entry<String, DispenseConfig.SurfaceMap> e : c.surfaces.entrySet()) {
            DispenseConfig.SurfaceMap m = e.getValue();
            if (m == null) {
                continue;
            }
            try {
                (used.contains(e.getKey()) ? z.allow : z.other).add(Zone.surface(e.getKey(), SurfacePlane.fit(
                        m.points), c));
            }
            catch (IllegalArgumentException notAPlane) {
                // fewer than 3 points or all on one line: I4 refuses every dot on it too
            }
        }
        List<String> names = new ArrayList<>();
        for (String n : used) {
            names.add("'" + n + "'");
        }
        String last = names.remove(names.size() - 1);
        z.where = "the waste, the towel, a cup, the purge row, the calibration area, a coupon, or the probed "
                + (names.isEmpty() ? "" : String.join(", ", names) + " or ") + last + " surface";
        return z;
    }

    /** A cup: "cup" or "scale" as a part of a place's name ("cup2", "Scale", "scale-cup", "cup_1"; not "cupboard"). */
    private static final Pattern CUP = Pattern.compile("(?i)(^|[-_])(cup|scale)([-_0-9]|$)");

    /** The places that take the hold's drool: the waste (by its setting), the towel, and a cup ({@link #CUP}). */
    static boolean isSinkPlace(DispenseConfig c, String name) {
        return name != null && (name.equalsIgnoreCase(c.wasteLocationName) || name.equalsIgnoreCase("towel")
                || CUP.matcher(name).find());
    }

    /** "forbidden: <name>", "board: <board>", the name of the allowed zone the point is in, or null when in none. */
    public String zoneAt(double x, double y) {
        Zone f = first(forbid, x, y);
        if (f != null) {
            return "forbidden: " + f.name;
        }
        String b = boards.on(x, y, boardMarginMm);
        if (b != null) {
            return "board: " + b;
        }
        Zone a = first(allow, x, y);
        return a == null ? null : a.name;
    }

    /**
     * The hold where the needle is: null when the valve may open with its paste landing at (x, y); else "the needle
     * (x, y) is …: park first (or start it with park: 'waste')", not suggesting a waste on or near a board.
     */
    public String refusal(double x, double y) {
        String why = whyHold(x, y);
        if (why == null) {
            return null;
        }
        boolean wasteNear = waste != null && boards.on(waste.x, waste.y, holdMarginMm) != null;
        return String.format(Locale.ROOT, "the needle (%.1f, %.1f) is %s: park first", x, y, why) + (wasteNear
                ? String.format(Locale.ROOT, ", over a place off the boards (the waste too lies within %.1f mm of one)",
                        holdMarginMm) : " (or start it with park: 'waste')");
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
        Zone f = first(forbid, x, y);
        if (f != null) {
            return at + String.format(Locale.ROOT, "over %s, where the valve and the screw stay off: set that place "
                    + "or surface %.0f mm or more away from it", f.label, f.c);
        }
        String b = onBoard(x, y);
        if (b != null) {
            return at + b;
        }
        return first(allow, x, y) != null ? null : at + why(x, y);
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
        Zone o = first(other, x, y);
        if (o != null) {
            return "over " + o.label + ", which takes no paste: paste goes only on " + where;
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

    /**
     * Why the pressure hold may not open the valve at (x, y), null when it may: only a sink takes its drool. Names the
     * zone the needle is over, allowed or not, as {@link #why} does.
     */
    private String whyHold(double x, double y) {
        Zone f = first(forbid, x, y);
        if (f != null) {
            return "over " + f.label + ", where the valve and the screw stay off";
        }
        // the hold has no own dots (a board run's are its program's), and its drool spreads: its circle more
        String b = boards.on(x, y, holdMarginMm);
        if (b != null) {
            return boards.on(x, y, boardMarginMm) != null ? "on " + b : String.format(Locale.ROOT, "within %.1f mm of "
                    + "%s, where its drool may reach the board", holdMarginMm, b);
        }
        for (Zone a : allow) {
            if (a.sink && a.contains(x, y)) {
                return null;
            }
        }
        Zone over = first(allow, x, y);
        over = over != null ? over : first(other, x, y);
        return (over != null ? "over " + over.label + ", " : "") + "not over a place that takes paste while the "
                + "pressure is held (the waste, the towel, a cup, the purge row, the calibration area or a coupon)";
    }

    /**
     * BR-81: why a program may not put paste at (x, y), on a board within the margin, and what to do; null off the
     * boards, and at a board run's planned dot on the outlines its registration knew.
     */
    private String onBoard(double x, double y) {
        String b = boards.on(x, y, boardMarginMm);
        if (b == null) {
            return null;
        }
        for (double[] d : ownDots) {
            if (Math.hypot(x - d[0], y - d[1]) <= OWN_DOT_MM) {
                String newer = boards.on(x, y, boardMarginMm, ownUpToMs);
                return newer == null ? null : "on " + newer + (ownUpToMs == null ? ", and this board run has no "
                        + "board registration: register the board" : ", recorded after this board run's registration "
                                + "(the bed changed since): register the board again");
            }
        }
        Zone a = first(allow, x, y);
        return "on " + b + ", where only a board run's planned dots may go: set " + (a != null && a.sink ? a.label
                : "it") + " again off the board";
    }

    /** The first of the zones that holds (x, y), or null. */
    private static Zone first(List<Zone> zones, double x, double y) {
        for (Zone z : zones) {
            if (z.contains(x, y)) {
                return z;
            }
        }
        return null;
    }
}
