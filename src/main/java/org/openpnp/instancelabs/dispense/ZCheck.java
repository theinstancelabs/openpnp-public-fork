package org.openpnp.instancelabs.dispense;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Z after a home (I4). A home can move the Z zero: it moved about 0.7 mm across an OpenPnP home on
 * 2026-10-28, board 2 then probed 0.85 mm low and the needle moved about twice the shift (the see-saw
 * maps z2 = 63 - z1, so the needle relation moves by 2 x the zero error). So Z data measured before
 * the last home (the needle frame, the touch-off, the surfaces a task uses) are accepted only after
 * one N1 touch at a stored point agrees within {@link #TOLERANCE_MM}. Pure; no OpenPnP state.
 *
 * <p>A touch vouches only for the data in the reference's Z frame: each datum carries the home it was
 * measured under ({@code homeAtMs}, {@link HomeTracker#frameHomeAtMs()}), and a home between two data
 * may have moved Z. So a point measured after an earlier home never clears older data, and a check
 * touches one point per frame among the stale data. A passing touch moves the data it vouched for to
 * the frame of the home it checked ({@link #restamp}). A datum of unknown frame is vouched for only
 * by a touch of its own point.</p>
 */
public final class ZCheck {
    private ZCheck() {
    }

    /**
     * Largest |touch - stored| that passes: just over one 0.025 mm fine step (the bench re-probe of
     * board 2 at 3 stored spots agreed within 0.025 mm); the needle then moves at most ~0.06 mm.
     */
    public static final double TOLERANCE_MM = 0.03;
    /** Name of the needle anchor's spot as a reference. */
    public static final String NEEDLE_FRAME = "needle frame";

    /** A stored N1 contact that can be touched again. */
    public static final class Reference {
        /** Surface name ("board", "blank") or {@link #NEEDLE_FRAME}. */
        public String name;
        /** Index of the point in its surface (0-based), -1 for the needle frame. */
        public int point;
        /** N1 tip machine X/Y and its stored contact Z. */
        public double x, y, z;
        /** When it was measured. */
        public Long atMs;
        /** Its Z frame (the home it was measured or last checked under), or null (unknown). */
        public Long homeAtMs;

        /** The datum it belongs to ({@link Stale#what}). */
        public String what() {
            return NEEDLE_FRAME.equals(name) ? NEEDLE_FRAME_DATUM : surfaceDatum(name);
        }
    }

    /** The latest Z check (memory only). */
    public static final class Result {
        public long atMs;
        /** The home this check is for. */
        public Long homeAtMs;
        public String refName;
        public double x, y, refZ, z, deltaMm;
        public boolean pass;
        /** When the reference was measured. */
        public Long refAtMs;
        /** The reference's Z frame (the home it was measured or last checked under), or null. */
        public Long refHomeAtMs;
        /**
         * Passed: the Z data measured before the home that this check vouches for (those in the frame of
         * the points it touched, and the reference's own datum), with what a pass for the same home
         * vouched for already.
         */
        public List<Stale> covers = new ArrayList<>();
    }

    /** A Z datum of a task's scope, with the time it was measured. */
    public static final class Stale {
        public String what;
        public Long atMs;
        /** Its Z frame (the home it was measured or last checked under), or null (unknown). */
        public Long homeAtMs;

        public Stale(String what, Long atMs, Long homeAtMs) {
            this.what = what;
            this.atMs = atMs;
            this.homeAtMs = homeAtMs;
        }

        /** The same datum (what, and when it was measured). */
        boolean same(Stale o) {
            return what.equals(o.what) && Objects.equals(atMs, o.atMs);
        }
    }

    static final String NEEDLE_FRAME_DATUM = "the needle frame";
    static final String TOUCH_OFF_DATUM = "the touch-off";

    static String surfaceDatum(String name) {
        return "the '" + name + "' surface";
    }

    /**
     * Stored N1 contacts measured before {@code lastHomeAtMs} (all of them when null), in this order:
     * the points of the board surface when it is tied to the plan's registration (its spots were
     * chosen bare), the points of the blank surface and those a probe after a home replaced
     * ({@link #kept}), the needle anchor's X/Y when they are set. A surface probed after the home is
     * in the new frame already and is no reference.
     */
    public static List<Reference> references(DispenseConfig cfg, String planBoardRegistration, Long lastHomeAtMs) {
        List<Reference> l = new ArrayList<>();
        DispenseConfig.SurfaceMap board = cfg.surface(cfg.boardSurfaceName);
        if (board != null && board.boardFingerprint != null && board.boardFingerprint.equals(planBoardRegistration)) {
            addPoints(l, cfg.boardSurfaceName, board, lastHomeAtMs);
        }
        if (!Objects.equals(cfg.blankLocationName, cfg.boardSurfaceName)) {
            DispenseConfig.SurfaceMap blank = cfg.surface(cfg.blankLocationName);
            addPoints(l, cfg.blankLocationName, blank, lastHomeAtMs);
            addPoints(l, cfg.blankLocationName, blank == null ? null : blank.replaced, lastHomeAtMs);
        }
        DispenseConfig.NeedleAnchor a = cfg.needleAnchor;
        if (a != null && a.xMm != null && a.yMm != null && before(a.setAtMs, lastHomeAtMs)) {
            Reference r = new Reference();
            r.name = NEEDLE_FRAME;
            r.point = -1;
            r.x = a.xMm;
            r.y = a.yMm;
            r.z = a.surfaceZMm;
            r.atMs = a.setAtMs;
            r.homeAtMs = a.homeAtMs;
            l.add(r);
        }
        return l;
    }

    private static void addPoints(List<Reference> l, String name, DispenseConfig.SurfaceMap m, Long home) {
        if (m == null || m.points == null || !before(m.setAtMs, home)) {
            return;
        }
        for (int i = 0; i < m.points.size(); i++) {
            DispenseConfig.SurfacePoint p = m.points.get(i);
            Reference r = new Reference();
            r.name = name;
            r.point = i;
            r.x = p.x;
            r.y = p.y;
            r.z = p.z;
            r.atMs = m.setAtMs;
            r.homeAtMs = m.homeAtMs;
            l.add(r);
        }
    }

    /**
     * What the blank keeps when it is probed again ({@link DispenseConfig.SurfaceMap#replaced}): the points it
     * replaces when they were measured before the last home in a known Z frame, so a later check of the older data
     * of that frame (the needle frame, the touch-off) still has a point to touch (review 1: an automatic blank probe
     * after a home took the only one); else what those points kept themselves. Null when nothing.
     */
    static DispenseConfig.SurfaceMap kept(DispenseConfig.SurfaceMap old, Long lastHomeAtMs) {
        if (old == null) {
            return null;
        }
        if (lastHomeAtMs != null && old.setAtMs != null && old.setAtMs < lastHomeAtMs && old.homeAtMs != null
                && old.points != null) {
            DispenseConfig.SurfaceMap k = new DispenseConfig.SurfaceMap();
            k.points = new ArrayList<>(old.points);
            k.setAtMs = old.setAtMs;
            k.homeAtMs = old.homeAtMs;
            return k;
        }
        return old.replaced;
    }

    /** Measured before the home (a datum without a time counts as before). */
    private static boolean before(Long atMs, Long homeAtMs) {
        return homeAtMs == null || atMs == null || atMs < homeAtMs;
    }

    /** Pass iff |z - ref.z| <= {@link #TOLERANCE_MM}. */
    public static boolean verdict(Reference ref, double z) {
        return Math.abs(z - ref.z) <= TOLERANCE_MM + 1e-9;
    }

    /** The check result of touching {@code ref} at Z {@code z}. */
    public static Result result(Reference ref, double z, long atMs, Long homeAtMs) {
        Result r = new Result();
        r.atMs = atMs;
        r.homeAtMs = homeAtMs;
        r.refName = ref.name;
        r.x = ref.x;
        r.y = ref.y;
        r.refZ = ref.z;
        r.z = z;
        r.deltaMm = z - ref.z;
        r.pass = verdict(ref, z);
        r.refAtMs = ref.atMs;
        r.refHomeAtMs = ref.homeAtMs;
        return r;
    }

    /**
     * Whether a passing touch of {@code ref} vouches for {@code d}: the same Z frame (no home between
     * them), or {@code d} is the reference's own datum.
     */
    public static boolean vouches(Reference ref, Stale d) {
        return (ref.homeAtMs != null && ref.homeAtMs.equals(d.homeAtMs))
                || (ref.what().equals(d.what) && Objects.equals(ref.atMs, d.atMs));
    }

    /**
     * {@code r} (a touch of {@code ref}) with what it vouches for when it passed: every Z datum of
     * {@code cfg} measured before its home that {@code ref} vouches for, and what {@code prev}, a pass
     * for the same home, vouched for already.
     */
    public static Result vouch(Result r, Reference ref, DispenseConfig cfg, Result prev) {
        if (!r.pass) {
            return r;
        }
        if (prev != null && prev.pass && r.homeAtMs != null && r.homeAtMs.equals(prev.homeAtMs)) {
            r.covers.addAll(prev.covers);
        }
        Collection<String> surfaces = cfg.surfaces == null ? new ArrayList<String>() : cfg.surfaces.keySet();
        for (Stale d : staleOf(cfg, surfaces, r.homeAtMs)) {
            if (vouches(ref, d) && !contains(r.covers, d)) {
                r.covers.add(d);
            }
        }
        return r;
    }

    /**
     * After a passing touch: the data it vouched for are in the frame of the home it checked, so the
     * next home needs one touch for them. Returns whether {@code c} changed.
     */
    public static boolean restamp(DispenseConfig c, Result r) {
        if (r == null || !r.pass || r.homeAtMs == null) {
            return false;
        }
        boolean changed = false;
        DispenseConfig.NeedleAnchor a = c.needleAnchor;
        if (a != null && contains(r.covers, new Stale(NEEDLE_FRAME_DATUM, a.setAtMs, null))) {
            changed |= !r.homeAtMs.equals(a.homeAtMs);
            a.homeAtMs = r.homeAtMs;
        }
        if (c.needleZOffsetSetAtMs != null && contains(r.covers, new Stale(TOUCH_OFF_DATUM, c.needleZOffsetSetAtMs,
                null))) {
            changed |= !r.homeAtMs.equals(c.needleZOffsetHomeAtMs);
            c.needleZOffsetHomeAtMs = r.homeAtMs;
        }
        if (c.surfaces != null) {
            for (Map.Entry<String, DispenseConfig.SurfaceMap> e : c.surfaces.entrySet()) {
                DispenseConfig.SurfaceMap m = e.getValue();
                if (m != null && contains(r.covers, new Stale(surfaceDatum(e.getKey()), m.setAtMs, null))) {
                    changed |= !r.homeAtMs.equals(m.homeAtMs);
                    m.homeAtMs = r.homeAtMs;
                }
            }
        }
        return changed;
    }

    private static boolean contains(List<Stale> l, Stale d) {
        for (Stale x : l) {
            if (x.same(d)) {
                return true;
            }
        }
        return false;
    }

    /** The data of {@code stale} that {@code check}, a pass for this home, does not vouch for (all without one). */
    public static List<Stale> unchecked(Result check, Long lastHomeAtMs, List<Stale> stale) {
        if (check == null || !check.pass || lastHomeAtMs == null || !lastHomeAtMs.equals(check.homeAtMs)) {
            return stale;
        }
        List<Stale> l = new ArrayList<>();
        for (Stale d : stale) {
            if (!contains(check.covers, d)) {
                l.add(d);
            }
        }
        return l;
    }

    /** The first reference that vouches for one of {@code open}, or null. */
    public static Reference first(List<Reference> refs, List<Stale> open) {
        for (Reference ref : refs) {
            if (vouchesForAny(ref, open)) {
                return ref;
            }
        }
        return null;
    }

    public static boolean vouchesForAny(Reference ref, List<Stale> open) {
        for (Stale d : open) {
            if (vouches(ref, d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many N1 touches the check makes when every spot it looks at is bare and every touch passes: the
     * first reference of each Z frame of {@code open}, in order, until each datum is vouched for (as the
     * task's loop). 0 when some datum has no reference left.
     */
    public static int touches(List<Reference> refs, List<Stale> open) {
        List<Stale> left = new ArrayList<>(open);
        int n = 0;
        for (Reference ref : refs) {
            if (left.isEmpty()) {
                break;
            }
            if (vouchesForAny(ref, left)) {
                n++;
                left.removeIf(d -> vouches(ref, d));
            }
        }
        return left.isEmpty() ? n : 0;
    }

    /** The data of {@code open} no reference vouches for: nothing measured in their frame is left to touch. */
    public static List<Stale> unreachable(List<Reference> refs, List<Stale> open) {
        List<Stale> l = new ArrayList<>();
        for (Stale d : open) {
            if (first(refs, Collections.singletonList(d)) == null) {
                l.add(d);
            }
        }
        return l;
    }

    /** True when {@code check} is a failed check for this home. */
    public static boolean failed(Result check, Long lastHomeAtMs) {
        return check != null && !check.pass && lastHomeAtMs != null && lastHomeAtMs.equals(check.homeAtMs);
    }

    /**
     * The Z data of a scope measured before the home: the needle frame, the touch-off (unless a newer
     * needle frame replaced it, {@link #touchOffReplaced}), and every surface the scope uses
     * ({@code surfaceName} and {@code moreSurfaces}). Places carry the time they were set, not a frame a
     * touch vouches for: a passing check covers them, and after a failed one each place set before the
     * home is set again ({@link #placeBefore}). Empty without a home.
     */
    public static List<Stale> stale(DispenseConfig cfg, Interlocks.Scope sc, Long lastHomeAtMs) {
        List<String> names = new ArrayList<>();
        if (sc != null) {
            if (sc.surfaceName != null) {
                names.add(sc.surfaceName);
            }
            for (Map.Entry<String, List<double[]>> e : sc.moreSurfaces.entrySet()) {
                if (!names.contains(e.getKey())) {
                    names.add(e.getKey());
                }
            }
        }
        return staleOf(cfg, names, lastHomeAtMs);
    }

    private static List<Stale> staleOf(DispenseConfig cfg, Collection<String> surfaces, Long lastHomeAtMs) {
        List<Stale> l = new ArrayList<>();
        if (lastHomeAtMs == null) {
            return l;
        }
        if (cfg.needleAnchor != null && cfg.needleAnchor.setAtMs < lastHomeAtMs) {
            l.add(new Stale(NEEDLE_FRAME_DATUM, cfg.needleAnchor.setAtMs, cfg.needleAnchor.homeAtMs));
        }
        if (cfg.needleZOffsetSetAtMs != null && cfg.needleZOffsetSetAtMs < lastHomeAtMs
                && !touchOffReplaced(cfg)) {
            l.add(new Stale(TOUCH_OFF_DATUM, cfg.needleZOffsetSetAtMs, cfg.needleZOffsetHomeAtMs));
        }
        for (String n : surfaces) {
            DispenseConfig.SurfaceMap m = cfg.surface(n);
            if (m != null && (m.setAtMs == null || m.setAtMs < lastHomeAtMs)) {
                l.add(new Stale(surfaceDatum(n), m.setAtMs, m.homeAtMs));
            }
        }
        return l;
    }

    /**
     * True when a needle frame recorded after the touch-off set the needle Z offset again (to the
     * frame's own offset, {@link DispenseService#setNeedleAnchor}): the touch-off is no longer a Z
     * datum of anything, and the frame is checked as itself. So recording the needle frame after a
     * home that moved Z replaces the touch-off it covers, and the touch-off can then run (review 1);
     * it is required again by I4 until it has.
     */
    static boolean touchOffReplaced(DispenseConfig cfg) {
        return cfg.needleAnchor != null && cfg.needleZOffsetSetAtMs != null
                && cfg.needleZOffsetSetAtMs < cfg.needleAnchor.setAtMs;
    }

    /** A place set before the home {@code lastHomeAtMs} (one saved without a time counts as before). */
    public static boolean placeBefore(DispenseConfig.NamedLocation loc, Long lastHomeAtMs) {
        return loc != null && lastHomeAtMs != null && (loc.setAtMs == null || loc.setAtMs < lastHomeAtMs);
    }

    /** The places of {@code cfg} set before the home while the last Z check of that home failed (else none). */
    public static List<String> movedPlaces(DispenseConfig cfg, Result check, Long lastHomeAtMs) {
        List<String> l = new ArrayList<>();
        if (!failed(check, lastHomeAtMs) || cfg.locations == null) {
            return l;
        }
        for (Map.Entry<String, DispenseConfig.NamedLocation> e : cfg.locations.entrySet()) {
            if (placeBefore(e.getValue(), lastHomeAtMs)) {
                l.add(e.getKey());
            }
        }
        return l;
    }

    /**
     * I4 violation (without the code): a place set before a home whose Z check failed. Its Z is off by the move like
     * the surfaces': the needle would dispense into it (or N1 probe from inside it).
     */
    public static String placeMovedMessage(String name, DispenseConfig.NamedLocation loc, Result r) {
        return String.format(Locale.ROOT, "place '%s' Z %.3f was taken %s, before the home at %s where Z moved %.2f mm: "
                + "take its Z again with N1 touching (Setup › Places)", name, loc.z, loc.setAtMs == null
                        ? "at an unknown time" : "at " + clock(loc.setAtMs), r.homeAtMs == null ? "?" : clock(r.homeAtMs),
                r.deltaMm);
    }

    /** I4 note when the task checks Z itself (the code "I4" included). */
    public static String checksFirstNote(Long lastHomeAtMs, boolean homesFirst) {
        if (homesFirst || lastHomeAtMs == null) {
            return "I4: homes first, then checks Z (one N1 touch)";
        }
        return "I4: Z data predate the home at " + clock(lastHomeAtMs) + ": checks Z first (one N1 touch)";
    }

    /** I4 violation (without the code): stale Z data and no passing check since the home. */
    public static String notCheckedMessage(List<Stale> stale, long lastHomeAtMs) {
        return named(stale) + (stale.size() == 1 ? " was" : " were") + " measured before the home at "
                + clock(lastHomeAtMs) + ", and Z has not been checked since: Check Z (one N1 touch, ≈ 20 s); "
                + "Run does it by itself";
    }

    /** I4 violation and Z-check refusal (without the code): the check after this home failed. */
    public static String movedMessage(Result r) {
        return String.format(Locale.ROOT, "Z moved %.2f mm at the home of %s: N1 touched %s at X %.2f, Y %.2f at Z "
                + "%.3f, where it touched %.3f%s. The needle frame, the touch-off and the surfaces are off by this "
                + "(the needle by about twice), and so are the places. Record the needle frame again with both tips "
                + "(Setup › Needle Z frame), probe the blank, take the places' Z again with N1 (Setup › Places), touch "
                + "off and register the board again.", r.deltaMm,
                r.homeAtMs == null ? "?" : clock(r.homeAtMs), describe(r.refName), r.x, r.y, r.z, r.refZ,
                r.refAtMs == null ? "" : " at " + clock(r.refAtMs));
    }

    /** Refusal (without the code): Z must be checked but nothing measured before the home is left. */
    public static String noReferenceMessage(Long lastHomeAtMs) {
        return "Z must be checked after the home at " + (lastHomeAtMs == null ? "?" : clock(lastHomeAtMs))
                + ", but no point measured before it is left to touch (no blank or board surface, no needle-frame "
                + "X/Y): record the needle frame again";
    }

    /**
     * Refusal (without the code): Z must be checked, but nothing measured in the frame of {@code d} (the
     * same home) is left to touch, and a point from another frame does not vouch for them.
     */
    public static String noFrameReferenceMessage(Long lastHomeAtMs, List<Stale> d) {
        return "Z must be checked after the home at " + (lastHomeAtMs == null ? "?" : clock(lastHomeAtMs))
                + ", but no point measured under the same home as " + named(d) + " is left to touch, and a "
                + "home in between may have moved Z: record the needle frame again and touch off";
    }

    /** "the needle frame (13:02), the touch-off (13:05) and the 'board' surface (13:40)". */
    private static String named(List<Stale> l) {
        List<String> parts = new ArrayList<>();
        for (Stale s : l) {
            parts.add(s.what + (s.atMs == null ? "" : " (" + clock(s.atMs) + ")"));
        }
        return join(parts);
    }

    /** "the blank", "the board", "the needle-frame spot". */
    public static String describe(String refName) {
        return NEEDLE_FRAME.equals(refName) ? "the needle-frame spot" : "the " + refName;
    }

    private static String join(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    /** Local wall-clock time of an epoch-ms instant, "14:19" (the texts' times). */
    public static String clock(long epochMs) {
        return CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }

    /** "10-28 13:02". */
    public static String dayClock(long epochMs) {
        return DAY_CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()));
    }
}
