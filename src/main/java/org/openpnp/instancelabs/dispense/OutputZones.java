package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BR-03: where the valve and the screw may switch on, in the needle's (N2) machine X/Y. Allowed: every place (waste,
 * towel, park, cup, ...: a circle of {@code placeRadiusMm}), each coupon's rectangle, the purge row and the
 * calibration area (each with {@code marginMm}), and every probed surface (engine-probed patches, the blank, the boards
 * of plans) by I4's own rule ({@link Interlocks#onSurface}: within {@code surfaceMarginMm} of its points), so I4 and
 * I12 never disagree about a dot. Forbidden, whatever else covers it: the bottom camera (a circle of
 * {@code forbidRadiusMm}) and the configured circles. With the valve on the needle never crosses a forbidden zone.
 */
public final class OutputZones {
    /** A circle, a box or a probed surface, in machine X/Y (mm). */
    public static final class Zone {
        public final String name;
        public final boolean circle;
        public final double a, b, c, d; // circle: cx, cy, r; box: x0, y0, x1, y1
        /** A probed surface's plane and the configuration whose I4 margin applies; null for a circle or a box. */
        private final SurfacePlane plane;
        private final DispenseConfig cfg;

        private Zone(String name, boolean circle, double a, double b, double c, double d, SurfacePlane plane,
                DispenseConfig cfg) {
            this.name = name;
            this.circle = circle;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            this.plane = plane;
            this.cfg = cfg;
        }

        static Zone circle(String name, double cx, double cy, double r) {
            return new Zone(name, true, cx, cy, r, 0, null, null);
        }

        static Zone box(String name, double x0, double y0, double x1, double y1, double margin) {
            return new Zone(name, false, Math.min(x0, x1) - margin, Math.min(y0, y1) - margin,
                    Math.max(x0, x1) + margin, Math.max(y0, y1) + margin, null, null);
        }

        static Zone surface(String name, SurfacePlane plane, DispenseConfig cfg) {
            return new Zone(name, false, 0, 0, 0, 0, plane, cfg);
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
            z.forbid.add(Zone.circle(BOTTOM_CAMERA, bottomCameraXY[0], bottomCameraXY[1], s.forbidRadiusMm));
        }
        if (s.forbid != null) {
            for (DispenseConfig.ForbidZone f : s.forbid) {
                if (f != null) {
                    z.forbid.add(Zone.circle(f.name == null ? "forbidden circle" : f.name, f.x, f.y, f.radiusMm));
                }
            }
        }
        for (Map.Entry<String, DispenseConfig.NamedLocation> e : c.locations.entrySet()) {
            if (e.getValue() != null) {
                z.allow.add(Zone.circle(e.getKey(), e.getValue().x, e.getValue().y, s.placeRadiusMm));
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
            try {
                z.allow.add(Zone.surface("surface " + e.getKey(), SurfacePlane.fit(m.points), c));
            }
            catch (IllegalArgumentException notAPlane) {
                // fewer than 3 points or all on one line: I4 refuses every dot on it too
            }
        }
        return z;
    }

    /** "forbidden: <name>", the name of the allowed zone the point is in, or null when it is in none. */
    public String zoneAt(double x, double y) {
        for (Zone f : forbid) {
            if (f.contains(x, y)) {
                return "forbidden: " + f.name;
            }
        }
        for (Zone a : allow) {
            if (a.contains(x, y)) {
                return a.name;
            }
        }
        return null;
    }

    /** Null when the valve or the screw may switch on with the needle at (x, y); else why not, in plain words. */
    public String refusal(double x, double y) {
        for (Zone f : forbid) {
            if (f.contains(x, y)) {
                return String.format(Locale.ROOT, "the needle (%.1f, %.1f) is over the %s, where the valve and the "
                        + "screw stay off: park first", x, y, f.name);
            }
        }
        for (Zone a : allow) {
            if (a.contains(x, y)) {
                return null;
            }
        }
        return String.format(Locale.ROOT, "the needle (%.1f, %.1f) is not over a place where paste may go (a place, "
                + "the purge row, the calibration area or a probed surface): park first", x, y);
    }

    /** Null when a straight move with the valve on never crosses a forbidden zone; else why. */
    public String crossing(double x0, double y0, double x1, double y1) {
        for (Zone f : forbid) {
            if (f.touchedBy(x0, y0, x1, y1)) {
                return String.format(Locale.ROOT, "the needle's move from (%.1f, %.1f) to (%.1f, %.1f) crosses the %s "
                        + "with the valve on", x0, y0, x1, y1, f.name);
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
