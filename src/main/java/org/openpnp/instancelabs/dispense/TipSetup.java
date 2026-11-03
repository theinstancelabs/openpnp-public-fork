package org.openpnp.instancelabs.dispense;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.openpnp.instancelabs.padprofile.Tip;

/**
 * One version of a tip setup (tip setups design §22.1): a dispenser and the needle on it, with what
 * the engine needs of the needle (bore, outer diameter, form, smallest dot) and the words of every
 * text. Rows of {@code tip_setups} are append-only; a setup's current definition is its highest
 * version. Its key is the tip key every pad type version and dose record names ({@link TipSpec#key}),
 * so the records made before schema 3 name their setup unchanged.
 *
 * <p>A setup is <b>complete</b> when it has its ID, OD, form and taper (not {@code unknown}); only a
 * complete setup can be mounted. Dispenser, gauge, form and taper never change once the setup has a
 * pad type version, a dose record or a mount (another needle is another setup).</p>
 */
public class TipSetup {
    public static final String FORM_BENT = "bent";
    public static final String FORM_STRAIGHT = "straight";
    public static final String FORM_UNKNOWN = "unknown";
    public static final String TAPER_PLASTIC = "tapered-plastic";
    public static final String TAPER_STEEL = "straight-steel";
    public static final String TAPER_UNKNOWN = "unknown";
    /** odSource: measured, or assumed (shown "from the chart"). */
    public static final String OD_MEASURED = "measured";
    public static final String OD_ASSUMED = "assumed";
    /** Which machine direction the bend points with the tip on. */
    public static final List<String> BEND_TOWARD = Collections.unmodifiableList(Arrays.asList("+x", "-x", "+y", "-y"));
    /** {@link #model} of the seeded setup: §1's model (P/DoseModel) describes it. */
    public static final String MODEL_BENCH = "bench-22g";
    public static final String DEFAULT_DISPENSER = "Vieweg DV-5005";
    /** Not a setup key: a route ({@code POST /api/tip-setups/undo-mount}). */
    public static final String RESERVED_KEY = "undo-mount";

    public long rowId;
    /** Identity, = the tip key on records ({@link TipSpec#KEY}); never changes. */
    public String key;
    public int version;
    /** The words in every text ("not dispensable with the 25G straight"): 1-40 characters, unique among live setups. */
    public String name;
    /** The dispenser the tip sits on: 1-60 characters; another dispenser is another setup. */
    public String dispenser = DEFAULT_DISPENSER;
    /** Part number (at most 60 characters) or null. */
    public String tipModel;
    /** Needle gauge 10-34, or null. */
    public Integer gauge;
    /** Bore at the end (mm, 0.05-2.0); null on placeholders. */
    public Double idMm;
    /** Outer diameter at the end (mm, above the ID, at most 3.0); null on placeholders. */
    public Double odMm;
    /** measured | assumed. */
    public String odSource;
    /** bent | straight | unknown (placeholders only). */
    public String form;
    /** Bend angle 1-90 deg when bent; null when straight or not recorded. */
    public Double bendDeg;
    /** +x | -x | +y | -y where the bend points with the tip on; null when straight or not recorded. */
    public String bendToward;
    /** tapered-plastic | straight-steel | unknown (placeholders only). */
    public String taper;
    /** Tube length from the hub (mm, 3-60), or null (not recorded). */
    public Double lengthMm;
    /** The smallest dot the tip takes reliably: 400, 800 or 1600 steps (R1). */
    public int minDotSteps = 400;
    /** {@link #MODEL_BENCH} when §1's model describes this tip (the seed only), else null; never set by hand. */
    public String model;
    /** Free text, at most 500 characters. */
    public String notes;
    /** A retired setup cannot be mounted (it can be brought back). */
    public boolean retired;
    /** Why this version was written (a correction, the settings file at the update, ...). */
    public String why;
    public long createdAtMs;
    public String operator;

    /** The seeded setup's notes (§22.1). */
    public static final String SEED_NOTES = "The bench tests of 2026-10-27/28 used this tip. OD from the chart, not "
            + "measured; length not recorded. Auger: Vieweg DFS16-A.";
    /** A placeholder's notes (§22.1 migration step 5). */
    public static final String PLACEHOLDER_NOTES = "Made from older records that name this tip: fill in its bore (ID) "
            + "before you use it.";
    /** The smallest dots a setup may take (R1): a quarter, half or one turn. */
    public static final List<Integer> MIN_DOT_STEPS = Collections.unmodifiableList(Arrays.asList(400, 800, 1600));
    public static final List<String> FORMS = Collections.unmodifiableList(Arrays.asList(FORM_BENT, FORM_STRAIGHT,
            FORM_UNKNOWN));
    public static final List<String> TAPERS = Collections.unmodifiableList(Arrays.asList(TAPER_PLASTIC, TAPER_STEEL,
            TAPER_UNKNOWN));
    public static final int MAX_NAME = 40, MAX_DISPENSER = 60, MAX_TIP_MODEL = 60, MAX_NOTES = 500;

    /** The problems of these fields with their texts (§22.1 "allowed"), empty when none: the engine-side check. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        if (key == null || !TipSpec.KEY.matcher(key).matches()) {
            p.add("The key must be lower-case letters, digits and '-' (at most 48).");
        }
        else if (RESERVED_KEY.equals(key)) {
            p.add("The key '" + RESERVED_KEY + "' is taken by the web page: choose another.");
        }
        if (name == null || name.trim().isEmpty() || name.trim().length() > MAX_NAME) {
            p.add("The name must be 1 to " + MAX_NAME + " characters.");
        }
        if (dispenser == null || dispenser.trim().isEmpty() || dispenser.trim().length() > MAX_DISPENSER) {
            p.add("The dispenser must be 1 to " + MAX_DISPENSER + " characters.");
        }
        if (tipModel != null && tipModel.trim().length() > MAX_TIP_MODEL) {
            p.add("The tip model must be at most " + MAX_TIP_MODEL + " characters.");
        }
        if (gauge != null && (gauge < 10 || gauge > 34)) {
            p.add("The gauge must be 10 to 34.");
        }
        if (idMm != null && !(idMm >= 0.05 && idMm <= 2.0)) {
            p.add("The bore (ID) must be 0.05 to 2.0 mm.");
        }
        if (odMm != null && !(odMm <= 3.0 && (idMm == null ? odMm > 0 : odMm > idMm))) {
            p.add("The outer diameter (OD) must be larger than the ID and at most 3.0 mm.");
        }
        if (odSource != null && !OD_MEASURED.equals(odSource) && !OD_ASSUMED.equals(odSource)) {
            p.add("The OD is measured or from the chart (assumed).");
        }
        if (odMm != null && odSource == null) {
            p.add("Say where the OD comes from: measured or from the chart.");
        }
        if (form == null || !FORMS.contains(form)) {
            p.add("The form is bent or straight.");
        }
        if (FORM_BENT.equals(form)) {
            if (bendDeg != null && !(bendDeg >= 1 && bendDeg <= 90)) {
                p.add("The bend angle must be 1 to 90°.");
            }
            if (bendToward != null && !BEND_TOWARD.contains(bendToward)) {
                p.add("The bend points toward +x, -x, +y or -y.");
            }
        }
        else if (bendDeg != null || bendToward != null) {
            p.add("Only a bent tip has a bend angle and a bend direction.");
        }
        if (taper == null || !TAPERS.contains(taper)) {
            p.add("The taper is tapered plastic or straight steel.");
        }
        if (lengthMm != null && !(lengthMm >= 3 && lengthMm <= 60)) {
            p.add("The tube length must be 3 to 60 mm.");
        }
        if (!MIN_DOT_STEPS.contains(minDotSteps)) {
            p.add("The smallest dot must be 400, 800 or 1600 steps (a quarter, half or one turn).");
        }
        if (model != null && !MODEL_BENCH.equals(model)) {
            p.add("The model is " + MODEL_BENCH + " or none.");
        }
        if (notes != null && notes.length() > MAX_NOTES) {
            p.add("The notes must be at most " + MAX_NOTES + " characters.");
        }
        return p;
    }

    /** True when the setup has ID, OD, form and taper (not unknown): only a complete setup can be mounted. */
    public boolean isComplete() {
        return missing() == null;
    }

    /** The first of ID, OD, form and taper the setup has not got yet ("ID", "OD", "form", "taper"), or null. */
    public String missing() {
        if (idMm == null) {
            return "ID";
        }
        if (odMm == null) {
            return "OD";
        }
        if (form == null || FORM_UNKNOWN.equals(form)) {
            return "form";
        }
        if (taper == null || TAPER_UNKNOWN.equals(taper)) {
            return "taper";
        }
        return null;
    }

    /**
     * One line for the views, e.g. "ADL AD22TTA-B · 22G · bent 30° · tapered plastic · ID 0.41 mm · OD 0.72 mm
     * (from the chart)".
     */
    public String summary() {
        List<String> l = new ArrayList<>();
        if (tipModel != null && !tipModel.trim().isEmpty()) {
            l.add(tipModel.trim());
        }
        if (gauge != null) {
            l.add(gauge + "G");
        }
        if (FORM_BENT.equals(form)) {
            l.add(bendDeg == null ? "bent" : "bent " + plain(bendDeg) + "°");
        }
        else if (FORM_STRAIGHT.equals(form)) {
            l.add("straight");
        }
        if (TAPER_PLASTIC.equals(taper)) {
            l.add("tapered plastic");
        }
        else if (TAPER_STEEL.equals(taper)) {
            l.add("straight steel");
        }
        if (lengthMm != null) {
            l.add(plain(lengthMm) + " mm long");
        }
        if (idMm != null) {
            l.add("ID " + plain(idMm) + " mm");
        }
        if (odMm != null) {
            l.add("OD " + plain(odMm) + " mm" + (OD_ASSUMED.equals(odSource) ? " (from the chart)" : ""));
        }
        if (l.isEmpty()) {
            l.add("not described yet");
        }
        return String.join(" · ", l);
    }

    /** 0.41 -> "0.41", 30.0 -> "30". */
    static String plain(double v) {
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** The engine's view of this version as {@code cfg.tip} (key, name, ID, OD, smallest dot, version, model, form). */
    public TipSpec toSpec() {
        TipSpec t = new TipSpec();
        t.key = key;
        t.name = name;
        if (idMm != null) {
            t.idMm = idMm;
        }
        if (odMm != null) {
            t.odMm = odMm;
        }
        t.minDotSteps = minDotSteps;
        t.setupVersion = version;
        t.model = model;
        t.dispenser = dispenser;
        t.form = form;
        t.bendDeg = bendDeg;
        t.bendToward = bendToward;
        return t;
    }

    /** A field-by-field copy (a new version starts from the current one). */
    public TipSetup copy() {
        TipSetup s = new TipSetup();
        s.rowId = rowId;
        s.key = key;
        s.version = version;
        s.name = name;
        s.dispenser = dispenser;
        s.tipModel = tipModel;
        s.gauge = gauge;
        s.idMm = idMm;
        s.odMm = odMm;
        s.odSource = odSource;
        s.form = form;
        s.bendDeg = bendDeg;
        s.bendToward = bendToward;
        s.taper = taper;
        s.lengthMm = lengthMm;
        s.minDotSteps = minDotSteps;
        s.model = model;
        s.notes = notes;
        s.retired = retired;
        s.why = why;
        s.createdAtMs = createdAtMs;
        s.operator = operator;
        return s;
    }

    /** The seeded setup of schema 3 (§22.1): ad22tta-b, "22G bent taper", ADL AD22TTA-B, model bench-22g. */
    public static TipSetup seed() {
        TipSetup s = new TipSetup();
        s.key = Tip.SEED_KEY;
        s.name = Tip.SEED_NAME;
        s.dispenser = DEFAULT_DISPENSER;
        s.tipModel = "ADL AD22TTA-B";
        s.gauge = 22;
        s.idMm = 0.41;
        s.odMm = 0.72;
        s.odSource = OD_ASSUMED;
        s.form = FORM_BENT;
        s.bendDeg = 30.0;
        s.bendToward = null;
        s.taper = TAPER_PLASTIC;
        s.lengthMm = null;
        s.minDotSteps = 400;
        s.model = MODEL_BENCH;
        s.notes = SEED_NOTES;
        s.why = "the tip in use before tip setups (schema 3)";
        s.operator = "seed";
        return s;
    }

    /**
     * A placeholder for a tip key that older records name (§22.1 migration step 5): name = the key, form and taper
     * unknown, no ID or OD, the notes asking for its bore; it cannot be mounted until it is completed.
     */
    public static TipSetup placeholder(String key) {
        TipSetup s = new TipSetup();
        s.key = key;
        s.name = key;
        s.dispenser = DEFAULT_DISPENSER;
        s.form = FORM_UNKNOWN;
        s.taper = TAPER_UNKNOWN;
        s.minDotSteps = 400;
        s.notes = PLACEHOLDER_NOTES;
        s.why = "older records name this tip";
        s.operator = "seed";
        return s;
    }
}
