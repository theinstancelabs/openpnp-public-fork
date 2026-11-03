package org.openpnp.instancelabs.dispense;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.openpnp.instancelabs.padprofile.DoseModel;

/**
 * The library of tip setups (tip setups design §22.1-22.3): what the mounted setup sees of every pad
 * type, the keys of new setups, which edits make another needle, a copy of another setup's dot plan,
 * the entry a new setup is parked with, and the rows of {@code tip_mounts}.
 *
 * <p>The mounted setup S's view <b>composes</b> each type key: the pad fields (name, family with
 * Retired, pattern, pad size and roundness, layout, pitch, t, k) from the newest version of any setup
 * ({@code padVersion}), the dose fields (status, dots, calibrated area, recipe, mass, origin, notes) from
 * S's newest version ({@code version}); a type with no version of S is a stand-in (dose fields cleared,
 * version 0, notes "Not calibrated for the {S} yet."). Creation order, as {@code currentPadTypes}.</p>
 */
public final class TipSetups {
    private TipSetups() {
    }

    /**
     * A row of {@code tip_mounts}: the history of what was on the dispenser. The config is the truth; a row that
     * failed to write is repaired at the next start ({@link #RECONCILE}).
     */
    public static final class Mount {
        /** Another setup mounted. */
        public static final String MOUNT = "mount";
        /** "I changed the needle" (and the first half of every mount). */
        public static final String NEEDLE = "needle";
        /** A mount undone on the operator's word. */
        public static final String UNDO = "undo";
        /** The start-up repair: the config names another setup than the newest row. */
        public static final String RECONCILE = "reconcile";
        /** The setup in use at the upgrade. */
        public static final String SEED = "seed";

        public long id;
        public long createdAtMs;
        /** mount | needle | undo | reconcile | seed. */
        public String kind;
        public String setupKey;
        public int setupVersion;
        public String fromSetupKey;
        /** The needle token of the change ({@link DispenseConfig#needleChangedAtMs}). */
        public Long needleChangedAtMs;
        /** N2's head offset in OpenPnP at the time (mm). */
        public Double headOffsetXMm, headOffsetYMm;
        /** The incoming setup's profile and landing, and what the needle change voided (coupons, job, task). */
        public String profileJson, landingJson, voidedJson;
        public String why;
        public boolean simulated;
        public String operator;
    }

    /**
     * The pad types as setup {@code setupKey} sees them (§22.2): composed per type key (pad fields of the newest
     * version of any setup, dose fields of the setup's own newest version, stand-ins), every row with
     * {@code setupKey} = {@code tipKey} = the setup, in creation order. {@code DispenseService.getPadTypes()} is this
     * for the mounted setup.
     */
    public static List<PadType> typesFor(DispenseDb db, String setupKey) throws SQLException {
        return db.currentPadTypes(setupKey);
    }

    /**
     * The key of a new setup (§22.1): from its gauge and form ({@code 25g-straight}, {@code 27g-bent45},
     * {@code 22g-taper}), made unique against {@code taken} with -2, -3; never {@link TipSetup#RESERVED_KEY}.
     */
    public static String deriveKey(TipSetup s, Collection<String> taken) {
        String gauge = s.gauge == null ? "tip" : s.gauge + "g";
        String form;
        if (TipSetup.FORM_BENT.equals(s.form)) {
            form = "bent" + (s.bendDeg == null ? "" : Long.toString(Math.round(s.bendDeg)));
        }
        else if (TipSetup.TAPER_PLASTIC.equals(s.taper)) {
            form = "taper";
        }
        else if (TipSetup.FORM_STRAIGHT.equals(s.form)) {
            form = "straight";
        }
        else {
            form = "tip";
        }
        String base = "tip".equals(gauge) && "tip".equals(form) ? "tip" : gauge + "-" + form;
        Set<String> used = new HashSet<>();
        if (taken != null) {
            for (String k : taken) {
                used.add(k == null ? null : k.toLowerCase(Locale.ROOT));
            }
        }
        used.add(TipSetup.RESERVED_KEY);
        String k = base;
        for (int i = 2; used.contains(k); i++) {
            k = base + "-" + i;
        }
        return k;
    }

    /** The text of an identity edit of a setup in use (§22.3): another needle is another setup. */
    public static final String ANOTHER_NEEDLE = "This makes it another needle: add a tip instead (Add a tip…, starting "
            + "from this one).";
    /** A correction of a setup in use differs by at most this from the recorded bore (mm; 22G 0.41, 25G 0.25). */
    public static final double ID_CORRECTION_MM = 0.03;

    /**
     * Whether a new version may replace {@code was} (§22.1, §22.3): field problems throw IllegalArgumentException
     * (400); identity fields (dispenser, gauge, form, taper) of a setup in use throw {@link EngineRefusal} 409
     * {@code another-needle}; a correction (tip model, ID within 0.03 mm, bend angle, length) without
     * {@code correction} throws 409 {@code correction}. Plain edits (name, OD, bend direction, smallest dot, notes)
     * pass. Filling in what a placeholder lacks (an unknown form or taper, no ID) is never another needle.
     *
     * @param inUse the setup has a pad type version, a dose record or a mount
     */
    public static void checkEdit(TipSetup was, TipSetup next, boolean inUse, boolean correction) {
        List<String> problems = next.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        if (was == null) {
            return;
        }
        if (!was.key.equals(next.key)) {
            throw new IllegalArgumentException("The key of a tip never changes.");
        }
        if (!Objects.equals(was.model, next.model)) {
            throw new IllegalArgumentException("The bench model is never set by hand.");
        }
        if (!inUse) {
            return;
        }
        boolean identity = !Objects.equals(was.dispenser, next.dispenser)
                || changed(was.gauge, next.gauge)
                || !TipSetup.FORM_UNKNOWN.equals(was.form) && !Objects.equals(was.form, next.form)
                || !TipSetup.TAPER_UNKNOWN.equals(was.taper) && !Objects.equals(was.taper, next.taper)
                || was.idMm != null && next.idMm != null && Math.abs(was.idMm - next.idMm) > ID_CORRECTION_MM + 1e-9
                || was.idMm != null && next.idMm == null;
        if (identity) {
            throw new EngineRefusal(409, "another-needle", ANOTHER_NEEDLE);
        }
        List<String> fixes = new ArrayList<>();
        if (!Objects.equals(trim(was.tipModel), trim(next.tipModel))) {
            fixes.add("tip model " + words(was.tipModel) + " → " + words(next.tipModel));
        }
        if (was.idMm != null && !Objects.equals(was.idMm, next.idMm)) {
            fixes.add("ID " + mm(was.idMm) + " → " + mm(next.idMm) + " mm");
        }
        if (!Objects.equals(was.bendDeg, next.bendDeg)) {
            fixes.add("bend " + deg(was.bendDeg) + " → " + deg(next.bendDeg));
        }
        if (!Objects.equals(was.lengthMm, next.lengthMm)) {
            fixes.add("length " + mm(was.lengthMm) + " → " + mm(next.lengthMm) + " mm");
        }
        if (!fixes.isEmpty() && !correction) {
            throw new EngineRefusal(409, "correction", "Correcting the " + was.name + "'s details ("
                    + String.join(", ", fixes) + ")? A different needle is a new tip: Add a tip…").with("fields", fixes);
        }
    }

    /** The corrections of an edit as the TIP event writes them ("ID 0.41 → 0.43 mm"); empty for none. */
    public static List<String> corrections(TipSetup was, TipSetup next) {
        List<String> fixes = new ArrayList<>();
        if (was == null) {
            return fixes;
        }
        if (!Objects.equals(trim(was.tipModel), trim(next.tipModel))) {
            fixes.add("tip model " + words(was.tipModel) + " → " + words(next.tipModel));
        }
        if (!Objects.equals(was.idMm, next.idMm)) {
            fixes.add("ID " + mm(was.idMm) + " → " + mm(next.idMm) + " mm");
        }
        if (!Objects.equals(was.odMm, next.odMm)) {
            fixes.add("OD " + mm(was.odMm) + " → " + mm(next.odMm) + " mm");
        }
        if (!Objects.equals(was.bendDeg, next.bendDeg)) {
            fixes.add("bend " + deg(was.bendDeg) + " → " + deg(next.bendDeg));
        }
        if (!Objects.equals(was.lengthMm, next.lengthMm)) {
            fixes.add("length " + mm(was.lengthMm) + " → " + mm(next.lengthMm) + " mm");
        }
        return fixes;
    }

    private static boolean changed(Integer a, Integer b) {
        return a != null && !a.equals(b);
    }

    private static String trim(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String words(String s) {
        return trim(s) == null ? "none" : trim(s);
    }

    private static String mm(Double v) {
        return v == null ? "none" : TipSetup.plain(v);
    }

    private static String deg(Double v) {
        return v == null ? "none" : TipSetup.plain(v) + "°";
    }

    /** The trim note of a setup's new entry. */
    public static final String NEW_TIP_NOTE = "new tip";

    /**
     * The {@code parkedTips} entry a setup gets when it is added (§22.1): a copy of {@code profile} (its name as
     * given), no needle frame, the live needle offset as a placeholder with both times null, a zero trim noted
     * "new tip", no landing.
     *
     * @param profileFrom the setup key the profile was copied from, or "default"
     */
    public static DispenseConfig.ParkedTip newEntry(DispenseConfig live, DispenseProfile profile, String profileFrom) {
        DispenseConfig.ParkedTip e = new DispenseConfig.ParkedTip();
        e.profile = profile == null ? new DispenseProfile() : profile.copy();
        e.needleAnchor = null;
        e.needleZOffsetMm = live.needleZOffsetMm;
        e.needleZOffsetSetAtMs = null;
        e.needleZOffsetHomeAtMs = null;
        e.needleXyTrim = new DispenseConfig.NeedleXyTrim();
        e.needleXyTrim.note = NEW_TIP_NOTE;
        e.needleLanding = null;
        e.parkedAtMs = null;
        e.profileFrom = profileFrom;
        return e;
    }

    /**
     * As {@link #newEntry(DispenseConfig, DispenseProfile, String)} with the profile named "{profile name} · {setup
     * name}" (§22.3: a recipe to start from, nothing measured).
     */
    public static DispenseConfig.ParkedTip newEntry(DispenseConfig live, DispenseProfile profile, String profileFrom,
            String setupName) {
        DispenseConfig.ParkedTip e = newEntry(live, profile, profileFrom);
        String base = e.profile.name == null || e.profile.name.trim().isEmpty() ? "profile" : e.profile.name.trim();
        e.profile.name = base + " · " + setupName;
        return e;
    }

    /**
     * The version a copy from another setup writes for {@code target} (§22.2): the pad fields of {@code own} (the
     * target's composed view), the source's dot plan (dotSteps, dotsPerPad, calAreaMm2), status provisional, origin
     * {@link PadType#ORIGIN_COPIED}, no recipe and no weighed mass, the notes naming the source. A dose below the
     * target's minDotSteps or against R1 or R6 throws IllegalArgumentException (400); a target version that is
     * characterized or validated throws {@link EngineRefusal} 409 ("a copy would make it provisional").
     */
    public static PadType copyOf(PadType source, String sourceName, PadType own, TipSetup target) {
        if (own != null && !own.standIn && own.status.atLeast(PadTypeStatus.CHARACTERIZED)) {
            throw new EngineRefusal(409, "refused", (own.name == null ? own.typeKey : own.name) + " is "
                    + statusWords(own.status) + " for the " + target.name + " already: a copy would make it provisional.");
        }
        if (source.dotSteps == null && source.dotsPerPad == null) {
            throw new IllegalArgumentException("The " + sourceName + "'s version " + source.version + " of "
                    + source.typeKey + " has no dot plan of its own to copy.");
        }
        PadType v = own != null ? own.copy() : source.copy();
        v.version = 0;
        v.rowId = 0;
        v.standIn = false;
        v.dotSteps = source.dotSteps;
        v.dotsPerPad = source.dotsPerPad;
        v.calAreaMm2 = source.calAreaMm2;
        v.status = PadTypeStatus.PROVISIONAL;
        v.origin = PadType.ORIGIN_COPIED;
        v.doseFp = null;
        v.massPerPadMg = null;
        v.tipKey = target.key;
        v.setupKey = target.key;
        v.notes = "Copied from the " + sourceName + " (v" + source.version + ", " + statusWords(source.status)
                + ") as a starting point: nothing was measured with the " + target.name + ".";
        String problem = v.doseProblem();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (v.dotSteps != null && v.dotSteps < target.minDotSteps) {
            throw new IllegalArgumentException("the " + target.name + " takes "
                    + DoseModel.turnWithArticle(target.minDotSteps) + " per dot at least (" + target.minDotSteps
                    + " steps), not " + v.dotSteps + " steps");
        }
        return v;
    }

    private static String statusWords(PadTypeStatus s) {
        return s == PadTypeStatus.VALIDATED ? "validated" : s.dbName.replace('-', ' ');
    }
}
