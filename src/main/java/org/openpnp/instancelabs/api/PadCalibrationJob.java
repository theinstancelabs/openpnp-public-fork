package org.openpnp.instancelabs.api;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.openpnp.instancelabs.dispense.Balance;
import org.openpnp.instancelabs.dispense.CalibrationArea;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.DoseBlocks;
import org.openpnp.instancelabs.dispense.DoseBlocksRequest;
import org.openpnp.instancelabs.dispense.DoseBlocksResult;
import org.openpnp.instancelabs.dispense.DoseRecord;
import org.openpnp.instancelabs.dispense.DoseRecords;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.EngineRefusal;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.LandingResult;
import org.openpnp.instancelabs.dispense.ManualBalance;
import org.openpnp.instancelabs.dispense.NeedleChange;
import org.openpnp.instancelabs.dispense.PadCalibrationRecord;
import org.openpnp.instancelabs.dispense.PadInstances;
import org.openpnp.instancelabs.dispense.PadLadder;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.ProbeTargets;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SurfacePlane;
import org.openpnp.instancelabs.dispense.SurfaceProbeRequest;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.dispense.VacuumProber;
import org.openpnp.instancelabs.dispense.WeighPlan;
import org.openpnp.instancelabs.dispense.ZCheck;
import org.openpnp.instancelabs.padprofile.Anchor;
import org.openpnp.instancelabs.padprofile.CheckInputs;
import org.openpnp.instancelabs.padprofile.Decider;
import org.openpnp.instancelabs.padprofile.DoseEvidence;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.LadderStats;
import org.openpnp.instancelabs.padprofile.LandingFit;
import org.openpnp.instancelabs.padprofile.Limits;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.Suggester;
import org.openpnp.instancelabs.padprofile.Suggestion;
import org.openpnp.instancelabs.padprofile.Texts;
import org.openpnp.instancelabs.padprofile.Tip;
import org.openpnp.instancelabs.padprofile.TypeInfo;
import org.openpnp.instancelabs.sim.SimBalance;
import org.pmw.tinylog.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * One pad calibration (PROFILE_CALIBRATION_DESIGN.md §7): a job of engine steps and one operator step.
 *
 * <ol>
 * <li>(at the start) the checks of §12.2, then the suggestions saved as provisional pad types with their
 * mappings and the plan made again with them;</li>
 * <li>blank: the calibration area's surface probed when it is missing, older than the last home or
 * connect, or does not cover the area;</li>
 * <li>coupons: each weigh coupon looked at (it is there, clean, the blank's colour, its margin around
 * the weigh grid) and touched 3 times with N1 (its own plane);</li>
 * <li>ladder: the control (the profile dose) first, then every dose the groups' plans need measured, on
 * the blank, imaged; a camera record per dose; a control off the bench stops the job;</li>
 * <li>weigh: the weigh dots on the coupons, imaged; a coupon record per coupon; the head parked over the
 * purge row;</li>
 * <li>readings (the operator, or a connected balance): weigh, wipe, weigh again per coupon;</li>
 * <li>decide: per group over every fresh record pooled ({@link Decider}); a {@code characterized}
 * version only when every check passes ({@link DispenseService#applyPadCalibration});</li>
 * <li>plan again (a plan source).</li>
 * </ol>
 *
 * <p>Each engine step is an ordinary engine task queued from this job's thread under its lock after the
 * stop flag (§7.8): a STOP stops the job first, then the engine. The by-hand path (§8) runs the ladder
 * or the weighing alone for one dose: the same steps and records, no decision.</p>
 *
 * <p>Tip setups (§22.3): the job belongs to the setup mounted and the needle token at its start; every engine step
 * and every record carries them, and a mount or a needle change meanwhile stops it with nothing saved. Two jobs of
 * records only: {@code measure: "tip"} (Measure its dots: every dose the tip takes on the blank, the profile's
 * weighed on two coupons, for its dot table) and {@code measure: "landing"} (Check where it lands: a locator dot,
 * then a purge and a row of 8 in a band of the calibration area, fitted and saved, or asked about).</p>
 */
final class PadCalibrationJob {
    static final String RUNNING = "running", AWAITING = "awaiting-reading", DECIDING = "deciding",
            PLANNING = "planning", DONE = "done", STOPPED = "stopped", CANCELLED = "cancelled", FAILED = "failed";
    static final String LADDER = "ladder", WEIGH = "weigh";
    /** The jobs of records only (tip setups design §22.3): Measure its dots, Check where it lands. */
    static final String MEASURE_TIP = "tip", MEASURE_LANDING = "landing";
    /** The landing check's steps: the locator dot, then the purge and the row. */
    static final String LOCATOR = "locator", ROW = "row";
    /** The halt of a job whose engine saw a STOP it did not (§7.8). */
    static final String STOPPED_SINCE_START = "the machine was stopped after this pad calibration was started: nothing "
            + "more was sent";
    /** The browser's attestations count this long (IL.attest, W/core.js). */
    static final long ATTEST_MS = 8 * 3600 * 1000L;
    /** A client clock this far ahead of the server's is still taken. */
    static final long SKEW_MS = 5 * 60 * 1000L;
    /** A coupon's look and three touches (§7.9), a camera frame, the field of view of the estimate (mm). */
    static final double COUPON_S = 1 + 3 * DispenseService.PROBE_POINT_ESTIMATE_S, CAPTURE_S = 0.9;
    static final double[] ESTIMATE_FOV_MM = {20.5, 11.6};
    /** How long a connected balance may take to settle. */
    static final long READ_TIMEOUT_MS = 30_000;
    /** The hop of a dose no group gives one for (mm): the ladder's widest pitch. */
    static final double DEFAULT_HOP_MM = PadLadder.MAX_PITCH_MM;
    /** The blank step touches this far inside the area's free band, and no closer to its used band (mm). */
    static final double BLANK_INSET_MM = 1.0;
    /** §22.2: a dose whose dot size the tip does not know is spaced for at least this, or its OD + 0.4 mm (mm). */
    static final double UNKNOWN_DOT_MM = 1.6;
    /** §22.2: a tip wider than the 22G's bore is counted with the bench mass x 1.5 until it is weighed. */
    static final double WIDE_BORE_MM = 0.41, WIDE_BORE_MASS = 1.5;

    /** What the operator asked for (POST body, or the preview's query). */
    static final class Request {
        String planId;
        String boardFile;
        JsonElement groups;
        List<String> typeKeys;
        List<String> steps;
        Integer doseSteps;
        Integer weighDots;
        boolean save = true;
        Map<String, String> suggestionIds = new LinkedHashMap<>();
        /** Check the suggestion ids (the start; the preview only when it was given some). */
        boolean checkIds = true;
        Double regulatorPsi;
        Long regulatorAtMs;
        Long pasteLifeAtMs;
        String operator;
        List<String> demotes = new ArrayList<>();
        /** {@link #MEASURE_TIP} | {@link #MEASURE_LANDING} (records only), or null: calibrate. */
        String measure;
        /** The landing check searches ±9 mm (offered after its locator found nothing). */
        boolean wide;
    }

    /** One group of the job and the plan saved for it. */
    static final class GroupPlan {
        String group;
        String label;
        String kind;
        String typeKey;
        String packageId;
        String suggestionId;
        int steps;
        int perPad;
        List<Integer> doses = new ArrayList<>();
        double hopMm;
        double areaMm2;
        double targetMg;
        boolean edited;
        boolean validated;
        int weighSteps;
        Decider.Decision decision;
        String outcome;
        String text;
        PadCalibrationRecord record;
    }

    /** One weigh coupon of the job. */
    static final class CouponPlan {
        String place;
        String label;
        int steps;
        int dots;
        double pitch;
        double d95;
        double expectedMg;
        double[] rect;
        List<double[]> grid;
        List<double[]> looks;
        DoseRecord record;
        DoseRecord mass;
    }

    /** One step of the job as the status shows it. */
    static final class Step {
        final String key;
        String state = "todo";
        String text;
        double estimateS;

        Step(String key) {
            this.key = key;
        }
    }

    /** The job was stopped or cancelled between or during its steps. */
    private static final class Halt extends Exception {
        private static final long serialVersionUID = 1L;

        Halt(String why) {
            super(why);
        }
    }

    final String id = "pc-" + System.currentTimeMillis();
    final long startedAtMs = System.currentTimeMillis();
    private final ApiContext ctx;
    private final PadProfileEndpoints profile;
    private final DispenseEndpoints dispense;
    private final DispenseService svc;
    final Request req;
    /** The by-hand step (ladder | weigh), or null: the whole job. */
    String manual;
    PlanStore.Entry entry;
    String boardFile;
    String boardName;
    PadProfileEndpoints.Checked checked;
    final List<ProfileCheck.Group> chosen = new ArrayList<>();
    final List<GroupPlan> groups = new ArrayList<>();
    final List<Map<String, Object>> skipped = new ArrayList<>();
    final List<String> typeKeys = new ArrayList<>();
    Map<Integer, DoseEvidence> evidence = new TreeMap<>();
    final List<Step> steps = new ArrayList<>();
    final List<PadLadder.Dose> ladderDoses = new ArrayList<>();
    PadLadder.Layout layout;
    CalibrationArea area;
    final List<CouponPlan> coupons = new ArrayList<>();
    final List<Integer> notWeighed = new ArrayList<>();
    boolean blankNeeded;
    String blankWhy;
    final List<double[]> blankTargets = new ArrayList<>();
    double blankZ;
    int purgeNeed;
    double estimatedSeconds;
    double pasteMg;
    HomeTracker.Plan home;
    /** Homes the job makes: the first engine step's when one is needed, every engine step's under "always". */
    int homes;
    DispenseService.ZCheckPlan zCheck;
    String refusal;
    /** refused | suggestion-stale */
    String refusalCode = "refused";
    final List<String> needs = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    /** The setup mounted and the needle token at the start (§22.3): every engine step and record carries them. */
    String setupKey;
    String setupName;
    Long needleSinceMs;
    /** The mounted tip at the start with its dot table: the sizes and masses of the doses it knows. */
    Tip tip;
    /** {@link #MEASURE_TIP} | {@link #MEASURE_LANDING}, or null. */
    String measure;
    // the landing check (§22.3)
    /** The calibration area as it was before the band was reserved, the band's layout, E and R. */
    CalibrationArea landingArea;
    PadLadder.LandingLayout landingLayout;
    double[] expected;
    double searchMm;

    // the run
    private final Object lock = new Object();
    /** How long the job waits for one engine step before it stops the engine and fails (ms). */
    long stepWaitMs = ApiContext.MAX_WAIT_MS * 3;
    /**
     * The engine's stop epoch when the job was prepared: a STOP after it (also one pressed while the start
     * saved the suggestions, before this job was the current one) halts the job before its next engine step.
     */
    long startEpoch;
    private volatile boolean stopRequested;
    private volatile boolean cancelRequested;
    private volatile String stopReason;
    volatile String state = RUNNING;
    volatile String step;
    volatile long awaitingSinceMs;
    volatile long endedAtMs;
    // written by the job's thread, read by the status (the maps and the coupons under the lock)
    final List<String> runs = new CopyOnWriteArrayList<>();
    final List<Long> recordIds = new CopyOnWriteArrayList<>();
    final Map<String, DoseBlocksResult.Crop> crops = new LinkedHashMap<>();
    final Map<String, Integer> cropSteps = new LinkedHashMap<>();
    final List<String> warnings = new CopyOnWriteArrayList<>();
    Boolean controlPass;
    String controlText;
    Double controlMedianMm2;
    String failure;
    String newPlanId;
    String planError;
    Map<String, Object> result;
    Balance balance;
    PadCalibrationJobs.Engine engine;
    /** The block steps' results (P7 reads the dots after each block switch). */
    final List<DoseBlocksResult> results = new CopyOnWriteArrayList<>();
    /** The result photos stored after each block step (§22.4). */
    final List<Photo> photos = new CopyOnWriteArrayList<>();
    /** What the landing check found (saved, asked about, or not found), and the text of its result. */
    volatile LandingResult landing;
    volatile String landingText;
    /** Why a mount or a needle change stopped the job (§22.3), the result's sentence; null otherwise. */
    volatile String tipHalt;
    /** The needle change that voided the coupons this job waited for (§22.3), or null. */
    volatile NeedleChange voidedBy;

    private PadCalibrationJob(ApiContext ctx, PadProfileEndpoints profile, DispenseEndpoints dispense,
            DispenseService svc, Request req) {
        this.ctx = ctx;
        this.profile = profile;
        this.dispense = dispense;
        this.svc = svc;
        this.req = req;
    }

    // ------------------------------------------------------------------------------------------
    // What it will do (§7.2, §7.9)
    // ------------------------------------------------------------------------------------------

    /** The job for a request, planned now (the preview and the start): its steps, dots, coupons and refusal. */
    static PadCalibrationJob prepare(ApiContext ctx, PadProfileEndpoints profile, DispenseEndpoints dispense,
            DispenseService svc, Request req) throws Exception {
        PadCalibrationJob j = new PadCalibrationJob(ctx, profile, dispense, svc, req);
        j.startEpoch = ctx.padCalJobs.engine(svc).stopEpoch();
        j.plan();
        return j;
    }

    private void plan() throws Exception {
        DispenseConfig c = svc.getConfig();
        // the setup and the needle this job belongs to (§22.3)
        setupKey = c.tip.key;
        setupName = c.tip.name;
        needleSinceMs = c.needleChangedAtMs;
        tip = svc.mountedTip();
        measure = req.measure;
        if (measure != null && !MEASURE_TIP.equals(measure) && !MEASURE_LANDING.equals(measure)) {
            throw ApiException.badRequest("'measure' must be \"tip\" (measure its dots) or \"landing\" (check where it "
                    + "lands), or left out (calibrate)");
        }
        if (req.steps != null && !req.steps.isEmpty()) {
            if (measure != null) {
                throw ApiException.badRequest("'measure' is a job of its own: leave 'steps' out");
            }
            if (req.steps.size() != 1 || !(LADDER.equals(req.steps.get(0)) || WEIGH.equals(req.steps.get(0)))) {
                throw ApiException.badRequest("'steps' must be [\"ladder\"] or [\"weigh\"] (the by-hand steps), or "
                        + "left out (the whole calibration)");
            }
            manual = req.steps.get(0);
        }
        if (MEASURE_LANDING.equals(measure)) {
            planLanding(c);
            steps();
            estimate(c);
            refusal = refusal(c);
            return;
        }
        if (manual != null) {
            planManual(c);
        }
        else if (MEASURE_TIP.equals(measure)) {
            planTip(c);
        }
        else {
            planGroups(c);
        }
        boolean measuring = MEASURE_TIP.equals(measure);
        int profileSteps = c.profile.netStepsPerDot;
        // the weighing: the chosen doses without a confirmed mass, on the coupons there are places for
        List<String> places = c.padCalibration.definedCouponPlaces(c);
        boolean weigh = manual == null || WEIGH.equals(manual);
        if (weigh) {
            Map<Integer, Integer> need = new TreeMap<>();
            for (GroupPlan g : groups) {
                if (manual != null || measuring || !massConfirmed(g.steps)) {
                    need.merge(g.steps, 1, Integer::sum);
                }
            }
            List<WeighPlan.Need> l = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : need.entrySet()) {
                l.add(new WeighPlan.Need(e.getKey(), e.getValue()));
            }
            WeighPlan.Allocation a = WeighPlan.allocate(l, profileSteps, massConfirmed(profileSteps), places.size());
            notWeighed.addAll(a.notWeighed);
            for (int k = 0; k < a.coupons.size(); k++) {
                coupons.add(coupon(c, places.get(k), a.coupons.get(k)));
            }
            if (!l.isEmpty() && places.size() == 1) {
                notes.add("Only " + places.get(0) + " is set: one weighing cannot confirm the mass, so the types stay "
                        + "provisional until a second weighing (set " + otherPlace(c, places.get(0)) + " in Setup › "
                        + "Places).");
            }
        }
        // the ladder: the doses without fresh passing camera results with enough hops, the control first
        boolean ladder = manual == null || LADDER.equals(manual);
        if (ladder) {
            Set<Integer> doses = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
            for (GroupPlan g : groups) {
                for (int s : g.doses) {
                    if (manual != null || measuring || needsOptical(s, c.padCalibration.minHops)) {
                        doses.add(s);
                    }
                }
            }
            if (!doses.isEmpty() || !opticalFresh(profileSteps)) {
                if (doses.contains(profileSteps)) {
                    ladderDoses.add(ladderDose(c, profileSteps, false));
                }
                else {
                    ladderDoses.add(ladderDose(c, profileSteps, true));
                }
                for (int s : doses) {
                    if (s != profileSteps) {
                        ladderDoses.add(ladderDose(c, s, false));
                    }
                }
            }
        }
        if (!ladderDoses.isEmpty()) {
            area = c.calibrationArea.effective(c);
            if (area != null) {
                layout = PadLadder.layout(area, ladderDoses);
                planBlank(c);
            }
        }
        purgeNeed = purgeNeeded(c);
        steps();
        estimate(c);
        refusal = refusal(c);
    }

    /** The whole job: the chosen groups of a plan or board file, each with the plan of its suggestion. */
    private void planGroups(DispenseConfig c) throws Exception {
        if (req.planId == null && req.boardFile == null) {
            throw ApiException.badRequest("give 'planId' (a plan) or 'boardFile' (a board file OpenPnP lists)");
        }
        checked = profile.checked(svc, req.planId, req.boardFile);
        entry = checked.entry;
        boardFile = checked.boardFile;
        boardName = entry != null ? entry.plan.boardName : checked.boardName;
        evidence = checked.in.evidence;
        List<ProfileCheck.Group> l;
        if (req.groups == null && req.typeKeys != null) {
            l = new ArrayList<>();
            for (ProfileCheck.Group g : checked.result.groups) {
                String key = g.suggestion != null && g.suggestion.typeKey != null ? g.suggestion.typeKey : g.typeKey;
                if (req.typeKeys.contains(key)) {
                    l.add(g);
                }
            }
        }
        else {
            l = PadProfileEndpoints.chosen(checked, req.groups, "save");
        }
        for (ProfileCheck.Group g : l) {
            Suggestion s = g.suggestion;
            if (s == null || !(Suggestion.NEW.equals(s.kind) || Suggestion.CALIBRATE_EXISTING.equals(s.kind))) {
                skipped.add(Views.map("group", g.group, "why", s != null && Suggestion.USE.equals(s.kind)
                        ? "a pad type of this size passes the gate already: use it" : g.shortText));
                continue;
            }
            chosen.add(g);
            GroupPlan p = new GroupPlan();
            p.group = g.group;
            p.label = g.label;
            p.kind = s.kind;
            p.typeKey = s.typeKey;
            p.packageId = g.packageIds.isEmpty() ? null : g.packageIds.get(0);
            p.suggestionId = s.id;
            p.steps = s.dots.steps;
            p.perPad = s.dots.perPad;
            p.weighSteps = p.steps;
            p.doses.addAll(s.calibration != null ? s.calibration.doses : java.util.Collections.singletonList(p.steps));
            p.hopMm = g.hopMm != null && g.hopMm > 0 ? g.hopMm : DEFAULT_HOP_MM;
            p.areaMm2 = g.aperture.areaMm2;
            p.targetMg = s.amount == null ? 0 : s.amount.targetMg;
            p.edited = "your plan".equals(s.planFrom);
            TypeInfo t = s.typeKey == null ? null : checked.in.types.get(s.typeKey);
            p.validated = t != null && TypeInfo.rank(t.status) == 3;
            groups.add(p);
            typeKeys.add(p.typeKey);
        }
    }

    /** The by-hand step (§8): one dose of some pad types, recorded; nothing decided. */
    private void planManual(DispenseConfig c) throws Exception {
        if (req.doseSteps == null || !DoseBlocks.isAllowed(req.doseSteps, c)) {
            throw ApiException.badRequest("'doseSteps' must be a dot the " + c.tip.name + " takes: 400, 800 or 1600 "
                    + "steps (" + c.tip.minDotSteps + " at least)");
        }
        if (req.typeKeys == null || req.typeKeys.isEmpty()) {
            throw ApiException.badRequest("'typeKeys': the pad types the " + DoseModel.turnNoun(req.doseSteps)
                    + " is measured for");
        }
        DispenseDb db = svc.getDb();
        for (String k : req.typeKeys) {
            if (!db.typeKeyExists(k)) {
                throw ApiException.badRequest("no pad type '" + k + "'");
            }
            typeKeys.add(k);
        }
        CheckInputs in = PadInstances.inputs(svc, new ArrayList<>(), false);
        evidence = in.evidence;
        GroupPlan p = new GroupPlan();
        p.group = null;
        p.label = String.join(", ", req.typeKeys);
        p.typeKey = req.typeKeys.get(0);
        p.steps = req.doseSteps;
        p.weighSteps = p.steps;
        p.perPad = 1;
        p.doses.add(req.doseSteps);
        p.hopMm = DEFAULT_HOP_MM;
        groups.add(p);
        if (req.planId != null) {
            entry = dispense.requirePlan(req.planId);
            boardName = entry.plan.boardName;
        }
    }

    /**
     * Measure its dots (§22.3): every dose the mounted tip takes laid on the blank, the profile's dose weighed on two
     * coupons; records only (its dot table and its mass per turn), no pad type changes.
     */
    private void planTip(DispenseConfig c) throws Exception {
        GroupPlan p = new GroupPlan();
        p.label = "the " + c.tip.name + "'s dots";
        p.steps = c.profile.netStepsPerDot;
        p.weighSteps = p.steps;
        p.perPad = 1;
        for (int s : DoseModel.ALLOWED_STEPS) {
            if (DoseBlocks.isAllowed(s, c)) {
                p.doses.add(s);
            }
        }
        // no board gives a hop: the dots are spaced as a dose of unknown size is, max(1.6 mm, OD + 0.4 mm) (§22.2)
        p.hopMm = unknownSpacingMm();
        groups.add(p);
        evidence = PadInstances.inputs(svc, new ArrayList<>(), false).evidence;
        if (req.planId != null) {
            entry = dispense.requirePlan(req.planId);
            boardName = entry.plan.boardName;
        }
    }

    /**
     * Check where it lands (§22.3): the band of the calibration area it reserves, E (the setup's own landing under N2's
     * head offset now, else 0, 0) and the search R; the blank's surface probed first when it is stale.
     */
    private void planLanding(DispenseConfig c) throws Exception {
        area = c.calibrationArea.effective(c);
        landingArea = area == null ? null : area.copy();
        DispenseConfig.NeedleLanding own = c.needleLanding;
        boolean ownNow = own != null && !moved(svc.headOffsetNow(), own.headOffsetXMm, own.headOffsetYMm);
        boolean straight = TipSetup.FORM_STRAIGHT.equals(c.tip.form);
        boolean pointed = TipSetup.FORM_BENT.equals(c.tip.form) && c.tip.bendToward != null;
        expected = svc.landingInEffect();
        searchMm = LandingFit.searchMm(ownNow, straight, pointed, req.wide);
        landingLayout = PadLadder.landingLayout(landingArea, searchMm, d95(c.profile.netStepsPerDot), fovMm(), null);
        if (area != null && landingLayout.problem == null) {
            planBlank(c);
        }
        if (req.planId != null) {
            entry = dispense.requirePlan(req.planId);
            boardName = entry.plan.boardName;
        }
    }

    /** The top camera's frame (mm): the engine's, else the bench camera's for the estimate. */
    private double[] fovMm() {
        double[] f = svc.topCameraFovMm();
        return f != null ? f : ESTIMATE_FOV_MM;
    }

    /** N2's head offset now differs from the one a value was set under (more than 0.001 mm); unknown now: no. */
    static boolean moved(double[] now, Double x, Double y) {
        return now != null && (x == null || y == null || Math.abs(now[0] - x) > 0.001 || Math.abs(now[1] - y) > 0.001);
    }

    private CouponPlan coupon(DispenseConfig c, String place, int s) {
        CouponPlan k = new CouponPlan();
        k.place = place;
        int at = c.padCalibration.couponPlaces.indexOf(place);
        k.label = "Coupon " + (at + 1);
        k.steps = s;
        Double weighed = Suggester.mgPerDot(evidence, s, tip);
        double mg = weighed != null ? weighed : countMgPerDot(s);
        k.dots = req.weighDots != null && manual != null ? WeighPlan.count(s, 1, req.weighDots)
                : WeighPlan.count(s, mg, c.padCalibration.weighTargetMg);
        k.expectedMg = k.dots * countMgPerDot(s);
        k.d95 = d95(s);
        k.pitch = PadLadder.pitch(hop(s), k.d95);
        k.rect = c.padCalibration.couponRect(c, place);
        if (k.rect != null) {
            k.grid = WeighPlan.grid(k.rect, k.dots, k.pitch, k.d95, c.padCalibration.couponMarginMm);
            if (k.grid != null) {
                k.looks = WeighPlan.looks(k.grid, Math.max(0, c.padCalibration.couponMarginMm
                        - PadLadder.couponCornerSettings().ringOuterMm));
            }
        }
        return k;
    }

    private static String otherPlace(DispenseConfig c, String place) {
        for (String p : c.padCalibration.couponPlaces) {
            if (!p.equals(place)) {
                return p;
            }
        }
        return "a second coupon place";
    }

    private PadLadder.Dose ladderDose(DispenseConfig c, int s, boolean control) {
        int hops = 0;
        DoseEvidence e = evidence.get(s);
        if (e != null && e.opticalFresh) {
            hops += e.hops;
        }
        for (CouponPlan k : coupons) {
            if (k.steps == s) {
                hops += k.dots - 1;
            }
        }
        int n = control ? PadLadder.CONTROL_DOTS : PadLadder.dotsFor(s, c.padCalibration, hops);
        String label = control ? "control (" + DoseModel.turnNoun(s) + ")" : DoseModel.turnNoun(s);
        return new PadLadder.Dose(s, n, hop(s), d95(s), label);
    }

    /** The groups' shortest pad-to-pad move of a dose (§7.2 "all": the hop is the shortest of the groups sharing it). */
    private double hop(int s) {
        double h = Double.NaN;
        for (GroupPlan g : groups) {
            if (g.doses.contains(s) && !(g.hopMm >= h)) {
                h = g.hopMm;
            }
        }
        return Double.isNaN(h) ? DEFAULT_HOP_MM : h;
    }

    /**
     * The dot size the spacing uses: the measured d95, else the tip's upper edge (the bench model's for the 22G, its
     * dot table's otherwise). A dose whose size the tip does not know is spaced for max(1.6 mm, OD + 0.4 mm) (§22.2):
     * a dot that size less the ladder's edge gap, so the row's pitch is at least that.
     */
    private double d95(int s) {
        DoseEvidence e = evidence.get(s);
        if (e != null && e.hasMeasuredDots()) {
            return Math.max(e.d95Mm, tip.knowsSize(s) ? tip.dLoMm(s) : 0);
        }
        return tip.knowsSize(s) ? tip.dHiMm(s) : unknownSpacingMm() - PadLadder.ROW_EDGE_GAP_MM;
    }

    /** §22.2: the spacing of dots whose size the tip does not know, max(1.6 mm, OD + 0.4 mm). */
    private double unknownSpacingMm() {
        return Math.max(UNKNOWN_DOT_MM, tip.odMm + 0.4);
    }

    /** The mass of a dot as the job counts and expects it: the tip's, else the bench's (x 1.5 above a 0.41 mm bore). */
    private double countMgPerDot(int s) {
        return countMgPerDot(tip, s);
    }

    /**
     * §22.2: the mass of a dot the tip knows, else the bench model's, x 1.5 when the bore is wider than the 22G's (a
     * count and R7's demand, not a claim).
     */
    static double countMgPerDot(Tip tip, int s) {
        if (tip.benchModel || tip.mgPerStep != null) {
            return tip.massMg(s); // the bench's, or the weighed mass per step of this tip (any of its doses)
        }
        return DoseModel.massMg(s) * (tip.idMm > WIDE_BORE_MM + 1e-9 ? WIDE_BORE_MASS : 1);
    }

    private boolean massConfirmed(int s) {
        DoseEvidence e = evidence.get(s);
        return e != null && e.massConfirmed;
    }

    private boolean opticalFresh(int s) {
        DoseEvidence e = evidence.get(s);
        return e != null && e.opticalFresh;
    }

    private boolean needsOptical(int s, int minHops) {
        DoseEvidence e = evidence.get(s);
        return e == null || !e.opticalFresh || !e.opticalPass || e.hops < minHops;
    }

    /**
     * Step 1: the area's surface is missing, older than the last home or connect, or does not cover the area's free
     * band (where the ladder goes: below it lie the dots of earlier ladders, which N1 must not touch).
     */
    private void planBlank(DispenseConfig c) {
        CalibrationArea free = freeBand(area);
        DispenseConfig.SurfaceMap m = c.surface(area.surfaceName);
        SurfacePlane plane = svc.surfacePlane(area.surfaceName);
        HomeTracker.Plan hp = svc.homePlan();
        Long homeAt = svc.getHomeTracker().getLastHomeAtMs();
        Long connected = svc.getControllerConnectedAtMs();
        if (m == null || plane == null) {
            blankWhy = "no '" + area.surfaceName + "' surface yet";
        }
        else if (hp.needed) {
            blankWhy = "it homes first, and the '" + area.surfaceName + "' surface predates that home";
        }
        else if (m.setAtMs != null && homeAt != null && m.setAtMs < homeAt) {
            blankWhy = "the '" + area.surfaceName + "' surface was probed before the last home";
        }
        else if (m.setAtMs != null && connected != null && m.setAtMs < connected) {
            blankWhy = "the '" + area.surfaceName + "' surface was probed before the controller last connected";
        }
        else {
            // where the probe below touches: a surface it made covers the area for the next job
            for (double[] p : areaCorners(free, BLANK_INSET_MM)) {
                if (plane.distanceOutside(p[0], p[1]) > 1.0 + 1e-9) {
                    blankWhy = "the '" + area.surfaceName + "' surface does not reach the calibration area's corners";
                    break;
                }
            }
        }
        blankNeeded = blankWhy != null;
        DispenseConfig.NamedLocation place = c.location(c.blankLocationName);
        blankZ = place != null ? place.z : c.needleAnchor != null ? c.needleAnchor.surfaceZMm : Double.NaN;
        if (blankNeeded) {
            try {
                if (c.purgeRow.isDefined() && area.surfaceName.equals(c.purgeRow.surfaceName)) {
                    for (VacuumProber.Target t : ProbeTargets.forRow(c, area.surfaceName)) {
                        blankTargets.add(new double[] {t.x, t.y});
                    }
                }
            }
            catch (Exception e) {
                // the area's own points below
            }
            blankTargets.addAll(areaCorners(free, BLANK_INSET_MM));
            blankTargets.add(new double[] {free.x0Mm + free.widthMm / 2, free.y0Mm + free.heightMm / 2});
            // the purge row's targets past the blank place lie in the area too
            blankTargets.removeIf(p -> inUsedBand(area, p));
            while (blankTargets.size() > 20) {
                blankTargets.remove(0);
            }
        }
    }

    /** The part of the area no ladder has used since the blank was wiped: from the used line up. */
    private static CalibrationArea freeBand(CalibrationArea a) {
        CalibrationArea f = a.copy();
        f.y0Mm = a.y0Mm + a.usedMm;
        f.heightMm = Math.max(0, a.heightMm() - a.usedMm);
        f.usedMm = 0;
        return f;
    }

    /** Within {@link #BLANK_INSET_MM} of the band of the area earlier ladders laid their dots in. */
    private static boolean inUsedBand(CalibrationArea a, double[] p) {
        double m = BLANK_INSET_MM - 1e-9;
        return a.usedMm > 0 && p[0] > a.x0Mm - m && p[0] < a.x0Mm + a.widthMm + m && p[1] > a.y0Mm - m
                && p[1] < a.y0Mm + a.usedMm + m;
    }

    private static List<double[]> areaCorners(CalibrationArea a, double inset) {
        List<double[]> l = new ArrayList<>();
        l.add(new double[] {a.x0Mm + inset, a.y0Mm + inset});
        l.add(new double[] {a.x0Mm + a.widthMm - inset, a.y0Mm + inset});
        l.add(new double[] {a.x0Mm + a.widthMm - inset, a.y0Mm + a.heightMm - inset});
        l.add(new double[] {a.x0Mm + inset, a.y0Mm + a.heightMm - inset});
        return l;
    }

    /** Purge-row spots of the ladder and the weighing (each its own pressure session). */
    private int purgeNeeded(DispenseConfig c) {
        int n = 0;
        if (layout != null) {
            n += DoseBlocks.purgeSpotsNeeded(runsOf(layout.blocks), c, c.padCalibration.flushDots);
        }
        if (!coupons.isEmpty()) {
            n += DoseBlocks.purgeSpotsNeeded(runsOf(weighBlocks(c)), c, c.padCalibration.flushDots);
        }
        return n;
    }

    private static List<DoseBlocks.Run> runsOf(List<DoseBlocksRequest.Block> blocks) {
        List<DoseBlocks.Run> l = new ArrayList<>();
        int at = 0;
        for (DoseBlocksRequest.Block b : blocks) {
            l.add(new DoseBlocks.Run(b.netSteps, at, b.xy.size()));
            at += b.xy.size();
        }
        return l;
    }

    /** The weigh step's blocks: one per coupon, in coupon order. */
    private List<DoseBlocksRequest.Block> weighBlocks(DispenseConfig c) {
        List<DoseBlocksRequest.Block> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(k.steps, DoseModel.turnNoun(k.steps) + " on "
                    + k.place, k.place);
            b.couponPlace = k.place;
            b.hopMm = k.pitch;
            b.matchRadiusMm = k.pitch / 2;
            if (k.grid != null) {
                b.xy.addAll(k.grid);
            }
            l.add(b);
        }
        return l;
    }

    private void steps() {
        if (MEASURE_LANDING.equals(measure)) {
            step("blank");
            step(LOCATOR);
            step(ROW);
            Step b = find("blank");
            if (!blankNeeded) {
                b.state = "skipped";
                DispenseConfig.SurfaceMap m = area == null ? null : svc.getConfig().surface(area.surfaceName);
                b.text = "blank surface probed" + (m != null && m.setAtMs != null ? " " + ZCheck.clock(m.setAtMs) : "")
                        + " (still valid)";
            }
            return;
        }
        boolean auto = manual == null && measure == null;
        if (manual == null || LADDER.equals(manual)) {
            step("blank");
        }
        if (!coupons.isEmpty()) {
            step("coupons");
        }
        if (manual == null || LADDER.equals(manual)) {
            step(LADDER);
        }
        if (!coupons.isEmpty()) {
            step(WEIGH);
            step("readings");
        }
        if (auto) {
            step("decide");
            step("plan");
        }
        Step b = find("blank");
        if (b != null && !blankNeeded) {
            b.state = "skipped";
            DispenseConfig.SurfaceMap m = area == null ? null : svc.getConfig().surface(area.surfaceName);
            b.text = layout == null ? "nothing to lay on the blank" : "blank surface probed"
                    + (m != null && m.setAtMs != null ? " " + ZCheck.clock(m.setAtMs) : "") + " (still valid)";
        }
        Step l = find(LADDER);
        if (l != null && layout == null) {
            l.state = "skipped";
            l.text = "every dose has fresh camera results on the blank";
        }
        Step p = find("plan");
        if (p != null && entry == null) {
            p.state = "skipped";
            p.text = "no plan to make again: plan the board";
        }
    }

    private void step(String key) {
        steps.add(new Step(key));
    }

    Step find(String key) {
        for (Step s : steps) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }

    /** Time, paste and the before-start sentence (§7.9). */
    private void estimate(DispenseConfig c) {
        home = svc.homePlan();
        zCheck = svc.zCheckPlan(null, home.needed);
        homes = DispenseConfig.HOME_ALWAYS.equals(c.homeBeforeRun) ? engineSteps() : home.needed ? 1 : 0;
        double homeS = homes * home.estimateS;
        double zS = zCheck.needed ? DispenseService.PROBE_POINT_ESTIMATE_S : 0;
        Step b = find("blank");
        if (b != null && blankNeeded) {
            b.estimateS = blankTargets.size() * DispenseService.PROBE_POINT_ESTIMATE_S;
        }
        Step k = find("coupons");
        if (k != null) {
            k.estimateS = coupons.size() * COUPON_S;
        }
        Step l = find(LADDER);
        double profileMg = countMgPerDot(c.profile.netStepsPerDot);
        if (MEASURE_LANDING.equals(measure)) {
            // a locator dot, then 8 purge dots and the row of 8, each step imaged before and after
            int frames = landingLayout == null ? 2 : Math.max(1, landingLayout.frames.size());
            find(LOCATOR).estimateS = 4 + 2 * frames * CAPTURE_S;
            find(ROW).estimateS = (PadLadder.LANDING_PURGE_DOTS + LandingFit.ROW_MM.length) * 1.2 + 2 * frames * CAPTURE_S;
            pasteMg = (1 + PadLadder.LANDING_PURGE_DOTS + LandingFit.ROW_MM.length) * profileMg;
        }
        if (l != null && layout != null) {
            l.estimateS = blockSeconds(c, layout.blocks);
            for (DoseBlocksRequest.Block x : layout.blocks) {
                pasteMg += x.xy.size() * countMgPerDot(x.netSteps);
            }
            pasteMg += DoseBlocks.purgeSpotsNeeded(runsOf(layout.blocks), c, c.padCalibration.flushDots) * profileMg;
        }
        Step w = find(WEIGH);
        if (w != null) {
            List<DoseBlocksRequest.Block> wb = weighBlocks(c);
            w.estimateS = blockSeconds(c, wb);
            for (CouponPlan x : coupons) {
                pasteMg += x.expectedMg;
            }
            pasteMg += DoseBlocks.purgeSpotsNeeded(runsOf(wb), c, c.padCalibration.flushDots) * profileMg;
        }
        double first = homeS + zS;
        estimatedSeconds = first;
        for (Step s : steps) {
            estimatedSeconds += s.estimateS;
        }
    }

    /** The engine steps: the blank probe, each coupon's probe and look, the ladder, the weighing. */
    private int engineSteps() {
        if (MEASURE_LANDING.equals(measure)) {
            return 2 + (blankNeeded ? 1 : 0);
        }
        int n = coupons.size() + (coupons.isEmpty() ? 0 : 1);
        if (find("blank") != null && blankNeeded) {
            n++;
        }
        if (find(LADDER) != null && layout != null) {
            n++;
        }
        return n;
    }

    /** A block program's estimate (the builder's own) and its camera frames before and after. */
    private double blockSeconds(DispenseConfig c, List<DoseBlocksRequest.Block> blocks) {
        int dots = 0;
        List<double[]> all = new ArrayList<>();
        for (DoseBlocksRequest.Block b : blocks) {
            dots += b.xy.size();
            all.addAll(b.xy);
        }
        double frames = 2 * PadLadder.tiles(all, ESTIMATE_FOV_MM, PadLadder.TILE_INSET_MM).size() * CAPTURE_S;
        try {
            return svc.estimateBlocks(blocks).estimatedSeconds + frames;
        }
        catch (Exception e) {
            return dots * 1.2 + frames;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Before the start (§12.2)
    // ------------------------------------------------------------------------------------------

    /** The first reason the job may not start, in §12.2's order, or null. Suggestion ids are {@link #staleRefusal}. */
    private String refusal(DispenseConfig c) {
        try {
            ctx.availableSvc();
            ctx.requireEngineIdle(svc);
            ctx.requireNoPressureHold();
            ctx.requireMachineIdle();
        }
        catch (ApiException e) {
            return e.getMessage();
        }
        PadCalibrationJob other = ctx.padCalJobs.current();
        if (other != null && !other.ended()) {
            return "a pad calibration is waiting for the readings of its coupons: type them, skip them or cancel it "
                    + "first (Calibrate › Pad calibration)";
        }
        if (MEASURE_LANDING.equals(measure)) {
            return landingRefusal(c);
        }
        if (groups.isEmpty()) {
            return nothingToCalibrate(c);
        }
        if (manual == null && measure == null && req.save && req.checkIds) {
            for (GroupPlan g : groups) {
                String want = req.suggestionIds.get(g.group);
                if (want == null || !want.equals(g.suggestionId)) {
                    refusalCode = PadProfileEndpoints.STALE;
                    return "The suggestion for " + g.label + " changed since it was shown (pad types, mappings, the "
                            + "tip or the calibration records changed): check the new one.";
                }
            }
        }
        if (!regulatorAttested(c)) {
            return String.format(Locale.ROOT, "Read the regulator first: it must show %.1f psi (Run › Checks › "
                    + "Regulator).", c.profile.pressurePsi);
        }
        long now = System.currentTimeMillis();
        if (req.pasteLifeAtMs == null || now - req.pasteLifeAtMs > ATTEST_MS || req.pasteLifeAtMs > now + SKEW_MS) {
            return "Confirm the paste is within its working life first (Run › Checks).";
        }
        // PUT /api/config keeps a value already below its floor: the machine never calibrates on the SIM's statistics
        List<String> floors = svc.isSimulatorConfirmed() ? new ArrayList<>() : c.padCalibration.realMachineProblems();
        if (!floors.isEmpty()) {
            return String.join("; ", floors) + ": set them back to calibrate on this machine";
        }
        if (!ladderDoses.isEmpty()) {
            if (area == null) {
                return "Set the blank place first (Setup › Places › " + c.blankLocationName + "): the calibration area "
                        + "lies on the blank.";
            }
            if (layout.problem != null) {
                return layout.problem;
            }
            String overlap = overlap(c);
            if (overlap != null) {
                return "The calibration area overlaps " + overlap + ": move it (Calibrate › Pad calibration).";
            }
            if (blankNeeded && Double.isNaN(blankZ)) {
                return "Set the blank place first (Setup › Places › " + c.blankLocationName + "): its Z is where N1 "
                        + "touches the blank.";
            }
        }
        boolean weighs = manual == null ? !notWeighed.isEmpty() || !coupons.isEmpty() : WEIGH.equals(manual);
        if (weighs && coupons.isEmpty()) {
            return "Set the weigh coupon places first: Setup › Places › " + String.join(" and ",
                    c.padCalibration.couponPlaces) + " (where the coupons lie).";
        }
        for (CouponPlan k : coupons) {
            if (k.rect == null) {
                return "Set the weigh coupon places first: Setup › Places › " + k.place + " (where the coupon lies).";
            }
            if (k.grid == null) {
                return String.format(Locale.ROOT, "%d %s dots %.1f mm apart do not fit on %s (%.0f × %.0f mm, %.0f mm "
                        + "margin): set padCalibration.couponSizeMm to the coupon's size, or a smaller weighTargetMg",
                        k.dots, DoseModel.turnName(k.steps), k.pitch, k.place, k.rect[2] - k.rect[0],
                        k.rect[3] - k.rect[1], c.padCalibration.couponMarginMm);
            }
            String over = couponOverlap(c, k.rect);
            if (over != null) {
                return "The weigh coupon place " + k.place + " overlaps " + over + ": move it (Setup › Places › "
                        + k.place + ").";
            }
            String why = svc.couponReservation(k.place);
            if (why != null) {
                return why;
            }
        }
        if (!coupons.isEmpty() && DispenseConfig.HOME_ALWAYS.equals(c.homeBeforeRun)) {
            // the weigh step would home after the coupon step, and the engine refuses coupon Z from before a home
            return "homeBeforeRun is 'always': every step homes first, so the coupons' Z would be from before the weigh "
                    + "step's home: set homeBeforeRun to 'whenNeeded' to weigh coupons";
        }
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (purgeNeed > 0 && (!row.isDefined() || row.spotsLeft() < purgeNeed)) {
            return !row.isDefined() ? "I9: the purge row is not set (origin, pitch, spots on the blank)"
                    : String.format(Locale.ROOT, "I9: only %d unused purge spot(s) left, %d needed: wipe the purge row "
                            + "and reset it", row.spotsLeft(), purgeNeed);
        }
        // the landing rule (§22.3): before where the needle lands is checked, only prime, the test dot and the landing
        // check dispense
        String xy = svc.needleXyProblem();
        if (xy != null) {
            return "I4: " + xy;
        }
        InterlockReport basic = svc.checkBasicInterlocks(svc.homePlan().needed);
        if (!basic.ok()) {
            return basic.toString();
        }
        return null;
    }

    /** The landing check's refusals (§22.3): the regulator and the paste, its band of the area, the interlocks. */
    private String landingRefusal(DispenseConfig c) {
        if (!regulatorAttested(c)) {
            return String.format(Locale.ROOT, "Read the regulator first: it must show %.1f psi (Run › Checks › "
                    + "Regulator).", c.profile.pressurePsi);
        }
        long now = System.currentTimeMillis();
        if (req.pasteLifeAtMs == null || now - req.pasteLifeAtMs > ATTEST_MS || req.pasteLifeAtMs > now + SKEW_MS) {
            return "Confirm the paste is within its working life first (Run › Checks).";
        }
        if (area == null) {
            return "Set the blank place first (Setup › Places › " + c.blankLocationName + "): the calibration area lies "
                    + "on the blank.";
        }
        if (landingLayout.problem != null) {
            return landingLayout.problem;
        }
        String overlap = overlap(c);
        if (overlap != null) {
            return "The calibration area overlaps " + overlap + ": move it (Calibrate › Pad calibration).";
        }
        if (blankNeeded && Double.isNaN(blankZ)) {
            return "Set the blank place first (Setup › Places › " + c.blankLocationName + "): its Z is where N1 touches "
                    + "the blank.";
        }
        InterlockReport basic = svc.checkBasicInterlocks(svc.homePlan().needed);
        if (!basic.ok()) {
            return basic.toString();
        }
        return null;
    }

    private String nothingToCalibrate(DispenseConfig c) {
        if (MEASURE_TIP.equals(measure)) {
            return "Nothing to measure: the " + c.tip.name + " takes no dot at this recipe.";
        }
        boolean use = false;
        for (Map<String, Object> s : skipped) {
            use |= String.valueOf(s.get("why")).startsWith("a pad type of this size");
        }
        return use ? "Nothing to calibrate: the chosen groups have a pad type of their size already (Use it)."
                : "Nothing to calibrate: every chosen group cannot be dispensed with the " + c.tip.name + ".";
    }

    private boolean regulatorAttested(DispenseConfig c) {
        long now = System.currentTimeMillis();
        if (req.regulatorPsi == null || req.regulatorAtMs == null
                || Math.abs(req.regulatorPsi - c.profile.pressurePsi) > 1e-6) {
            return false;
        }
        if (now - req.regulatorAtMs > ATTEST_MS || req.regulatorAtMs > now + SKEW_MS) {
            return false;
        }
        return c.needleChangedAtMs == null || req.regulatorAtMs + SKEW_MS >= c.needleChangedAtMs;
    }

    /** What the calibration area overlaps: the purge row, a coupon place, the plan's pads + 3 mm; null when nothing. */
    private String overlap(DispenseConfig c) {
        double x0 = area.x0Mm, x1 = area.x0Mm + area.widthMm, y0 = area.y0Mm, y1 = area.y0Mm + area.heightMm;
        if (nearPurgeRow(c, x0, y0, x1, y1)) {
            return "the purge row";
        }
        for (String p : c.padCalibration.couponPlaces) {
            double[] r = c.padCalibration.couponRect(c, p);
            if (r != null && distance(x0, y0, x1, y1, r[0], r[1], r[2], r[3])
                    < CalibrationArea.COUPON_CLEARANCE_MM - 1e-9) {
                return "the weigh coupon place " + p;
            }
        }
        String pads = overPads(x0, y0, x1, y1);
        return pads != null ? pads : overJobBoards(x0, y0, x1, y1);
    }

    /**
     * §22.3: "the board {name} of OpenPnP's job (3 mm around it)" when the box meets the outline of a board of the job
     * open in OpenPnP + 3 mm, with or without a plan; else null (also when no job is open).
     */
    private static String overJobBoards(double x0, double y0, double x1, double y1) {
        List<Object[]> boards;
        try {
            boards = org.openpnp.instancelabs.board.EdtRunner.call(PadCalibrationJob::jobBoardBoxes,
                    DispenseEndpoints.EDT_START_MS);
        }
        catch (Exception e) {
            return null; // OpenPnP's thread did not answer: the plan's pads were checked above
        }
        for (Object[] b : boards) {
            double[] r = (double[]) b[1];
            if (distance(x0, y0, x1, y1, r[0] - 3, r[1] - 3, r[2] + 3, r[3] + 3) <= 0) {
                return "the board " + b[0] + " of OpenPnP's job (3 mm around it)";
            }
        }
        return null;
    }

    /**
     * The outline boxes {name, {x0, y0, x1, y1}} of the enabled boards of the job open in OpenPnP (machine mm), as
     * wide as the board's dimensions turned by its rotation on either side; boards without dimensions are left out.
     */
    static List<Object[]> jobBoardBoxes() {
        List<Object[]> l = new ArrayList<>();
        org.openpnp.model.Job j = DispenseEndpoints.currentJob();
        if (j == null) {
            return l;
        }
        for (org.openpnp.model.BoardLocation bl : j.getBoardLocations()) {
            org.openpnp.model.Board b = bl.getBoard();
            if (!bl.isEnabled() || b == null || b.getDimensions() == null) {
                continue;
            }
            org.openpnp.model.Location at = bl.getGlobalLocation().convertToUnits(
                    org.openpnp.model.LengthUnit.Millimeters);
            org.openpnp.model.Location d = b.getDimensions().convertToUnits(org.openpnp.model.LengthUnit.Millimeters);
            if (!(d.getX() > 0) || !(d.getY() > 0)) {
                continue;
            }
            double a = Math.toRadians(at.getRotation());
            double ca = Math.cos(a), sa = Math.sin(a);
            double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int side : new int[] {1, -1}) {
                for (double[] p : new double[][] {{0, 0}, {d.getX(), 0}, {0, d.getY()}, {d.getX(), d.getY()}}) {
                    double x = side * p[0], y = p[1];
                    double gx = at.getX() + x * ca - y * sa, gy = at.getY() + x * sa + y * ca;
                    box[0] = Math.min(box[0], gx);
                    box[1] = Math.min(box[1], gy);
                    box[2] = Math.max(box[2], gx);
                    box[3] = Math.max(box[3], gy);
                }
            }
            l.add(new Object[] {b.getName(), box});
        }
        return l;
    }

    /**
     * What a weigh coupon's place overlaps: the purge row (its dots and the needle's Z are the blank's, under a
     * coupon), the plan's pads + 3 mm (the board); null when nothing.
     */
    private String couponOverlap(DispenseConfig c, double[] r) {
        return nearPurgeRow(c, r[0], r[1], r[2], r[3]) ? "the purge row" : overPads(r[0], r[1], r[2], r[3]);
    }

    /** A purge spot lies within {@link CalibrationArea#PURGE_CLEARANCE_MM} of the box. */
    private static boolean nearPurgeRow(DispenseConfig c, double x0, double y0, double x1, double y1) {
        DispenseConfig.PurgeRow row = c.purgeRow;
        if (row.isDefined()) {
            for (int i = 0; i < row.spots; i++) {
                double[] s = row.spot(i);
                if (distance(x0, y0, x1, y1, s[0], s[1], s[0], s[1]) < CalibrationArea.PURGE_CLEARANCE_MM - 1e-9) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "the pads of {board} (3 mm around them)" when the box meets the plan's pads + 3 mm, else null. */
    private String overPads(double x0, double y0, double x1, double y1) {
        if (entry != null) {
            for (DispensePlan.PlannedPad p : entry.plan.pads) {
                double h = Math.max(p.wMm, p.hMm) / 2 + 3;
                if (distance(x0, y0, x1, y1, p.xMm - h, p.yMm - h, p.xMm + h, p.yMm + h) <= 0) {
                    return "the pads of " + entry.plan.boardName + " (3 mm around them)";
                }
            }
        }
        return null;
    }

    private static double distance(double ax0, double ay0, double ax1, double ay1, double bx0, double by0, double bx1,
            double by1) {
        double dx = Math.max(0, Math.max(bx0 - ax1, ax0 - bx1));
        double dy = Math.max(0, Math.max(by0 - ay1, ay0 - by1));
        return Math.hypot(dx, dy);
    }

    // ------------------------------------------------------------------------------------------
    // The run
    // ------------------------------------------------------------------------------------------

    /** Step 0 on the request thread (§7.2): the suggestions saved as provisional types with their mappings. */
    void save() throws Exception {
        if (manual != null || !req.save || chosen.isEmpty()) {
            return;
        }
        Map<String, Object> w = profile.write(svc, checked, chosen, "save", req.operator);
        Object newId = w.get("newPlanId");
        if (newId != null) {
            PlanStore.Entry e = ctx.plans.get(String.valueOf(newId));
            if (e != null) {
                entry = e;
            }
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> applied = (List<Map<String, Object>>) w.get("applied");
        for (Map<String, Object> a : applied) {
            for (GroupPlan g : groups) {
                if (g.group.equals(a.get("group")) && a.get("typeKey") != null) {
                    g.typeKey = String.valueOf(a.get("typeKey"));
                }
            }
        }
        if (w.get("planError") != null) {
            warnings.add(String.valueOf(w.get("planError")));
        }
        svc.getDb().event(null, "INFO", "PCAL", new Gson().toJson(Views.map("job", id, "step", "save", "applied",
                applied, "newPlanId", newId)));
    }

    /** The job's steps on its own thread. */
    void run() {
        DispenseConfig c = svc.getConfig();
        engine = ctx.padCalJobs.engine(svc);
        try {
            balance = ctx.padCalJobs.balance();
            if (find("blank") != null && blankNeeded) {
                begin("blank");
                SurfaceProbeRequest r = new SurfaceProbeRequest();
                r.surfaceName = area.surfaceName;
                r.nominalZ = blankZ;
                r.targets = blankTargets;
                SurfaceProbeResult res = engine(() -> engine.probeSurface(r), x -> x.run, x -> x.ok, x -> x.message);
                done("blank", String.format(Locale.ROOT, "blank surface probed: %d points, Z %.3f..%.3f",
                        res.points.size(), res.plane.minZ, res.plane.maxZ));
            }
            if (MEASURE_LANDING.equals(measure)) {
                runLanding(c);
                end(DONE, null);
                return;
            }
            if (!coupons.isEmpty()) {
                begin("coupons");
                for (CouponPlan k : coupons) {
                    SurfaceProbeRequest r = couponProbe(c, k);
                    engine(() -> engine.probeSurface(r), x -> x.run, x -> x.ok, x -> x.message);
                }
                done("coupons", couponWords() + " found and touched");
            }
            if (layout != null) {
                begin(LADDER);
                DoseBlocksRequest r = new DoseBlocksRequest();
                r.kind = DoseBlocksRequest.LADDER;
                r.blocks.addAll(layout.blocks);
                r.areaReserveMm = layout.reservedMm;
                r.parkAfter = coupons.isEmpty();
                stamp(r);
                DoseBlocksResult res = engine(() -> engine.runDoseBlocks(r), x -> x.run, x -> x.ok, x -> x.message);
                results.add(res);
                storePhotos(res);
                List<DoseRecord> written = records(res, DoseRecord.OPTICAL);
                control(c, written);
                done(LADDER, ladderWords(res));
                if (Boolean.FALSE.equals(controlPass)) {
                    throw new IllegalStateException(controlText);
                }
                reweigh(c);
            }
            if (!coupons.isEmpty()) {
                begin(WEIGH);
                DoseBlocksRequest r = new DoseBlocksRequest();
                r.kind = DoseBlocksRequest.WEIGH;
                r.blocks.addAll(weighBlocks(c));
                stamp(r);
                DoseBlocksResult res = engine(() -> engine.runDoseBlocks(r), x -> x.run, x -> x.ok, x -> x.message);
                results.add(res);
                storePhotos(res);
                List<DoseRecord> written = records(res, DoseRecord.COUPON);
                synchronized (lock) {
                    for (int i = 0; i < written.size() && i < coupons.size(); i++) {
                        coupons.get(i).record = written.get(i);
                    }
                }
                done(WEIGH, weighWords());
                begin("readings");
                if (balance.isConnected()) {
                    readBalance();
                }
                else {
                    synchronized (lock) {
                        if (stopRequested) {
                            throw new Halt(stopReason);
                        }
                        awaitingSinceMs = System.currentTimeMillis();
                        state = AWAITING;
                    }
                    return;
                }
            }
            finish();
        }
        catch (Halt h) {
            halted();
        }
        catch (Throwable e) {
            fail(e); // an Error too: a job left running would refuse every other dispense task for good
        }
    }

    @FunctionalInterface
    private interface Submit<T> {
        Future<T> submit() throws Exception;
    }

    /**
     * One engine step: queued under the job's lock after the stop flag (a STOP since refuses it at the
     * engine), awaited, and ended by its result: a STOP or a cancel halts the job, a refusal or an error
     * fails it.
     */
    private <T> T engine(Submit<T> submit, java.util.function.Function<T, RunResult> run,
            java.util.function.Predicate<T> ok, java.util.function.Function<T, String> message) throws Exception {
        waitMachineIdle();
        Future<T> f;
        synchronized (lock) {
            if (!stopRequested && engine.stopEpoch() != startEpoch) {
                // a STOP the job did not see itself: pressed while the start saved the suggestions, or OpenPnP's Stop
                stopRequested = true;
                stopReason = STOPPED_SINCE_START;
            }
            String changed = stopRequested ? null : tipOrNeedleChanged();
            if (changed != null) {
                haltForTip(changed);
            }
            if (stopRequested) {
                throw new Halt(stopReason);
            }
            ctx.tracker.expect("pad calibration");
            f = submit.submit();
        }
        T res;
        try {
            res = f.get(stepWaitMs, TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException e) {
            // a step the job gives up on must not lay its dots later (still queued) or go on alone: stopped as by STOP
            // (which also ends it when OpenPnP dropped it: DispenseService.releaseDroppedTask)
            engine.abort("the pad calibration gave up waiting for its " + stepWords() + " step");
            throw new IllegalStateException("the " + stepWords() + " step did not end within " + stepWaitMs / 60000
                    + " min: the engine was stopped", e);
        }
        catch (java.util.concurrent.CancellationException e) {
            throw new IllegalStateException("OpenPnP dropped the " + stepWords() + " step before it started (a task of "
                    + "its own ahead of it failed): nothing was sent", e);
        }
        RunResult r = run.apply(res);
        if (r != null) {
            runs.add(r.runId);
        }
        if (stopRequested || r != null && r.status == RunResult.Status.ABORTED) {
            throw new Halt(stopReason != null ? stopReason : message.apply(res));
        }
        if (!ok.test(res)) {
            // the machine thread refuses a step of another setup or needle (§22.3): the job stops, nothing is saved
            String changed = tipOrNeedleChanged();
            if (changed != null) {
                synchronized (lock) {
                    haltForTip(changed);
                }
            }
            throw new IllegalStateException(message.apply(res));
        }
        return res;
    }

    /** The job's setup and needle on a dose-blocks step (§22.3) and the stop epoch of its start. */
    private void stamp(DoseBlocksRequest r) {
        r.stopEpoch = startEpoch;
        r.forTip = setupKey;
        r.needleSinceMs = needleSinceMs;
    }

    /**
     * §22.3: the text when another setup was mounted or the needle was changed since the job started ("The tip changed
     * during the calibration (the 25G straight was mounted at 14:07): nothing was saved."), else null.
     */
    String tipOrNeedleChanged() {
        DispenseConfig c = svc.getConfig();
        if (setupKey != null && !setupKey.equals(c.tip.key)) {
            Long at = null;
            try {
                at = TipSetupEndpoints.mountedAtMs(svc.getDb(), c.tip.key);
            }
            catch (Exception e) {
                at = null; // the text without the time
            }
            return "The tip changed during the calibration (the " + c.tip.name + " was mounted"
                    + (at == null ? "" : " at " + ZCheck.clock(at)) + "): nothing was saved.";
        }
        if (setupKey != null && !java.util.Objects.equals(needleSinceMs, c.needleChangedAtMs)) {
            return needleChangedDuring(c.needleChangedAtMs == null ? System.currentTimeMillis() : c.needleChangedAtMs);
        }
        return null;
    }

    /** "The needle was changed at {HH:MM} during the calibration: nothing was saved." (§22.3) */
    static String needleChangedDuring(long atMs) {
        return "The needle was changed at " + ZCheck.clock(atMs) + " during the calibration: nothing was saved.";
    }

    /** Under the lock: the job halts at once with the tip's or the needle's words (its result's sentence). */
    private void haltForTip(String why) throws Halt {
        stopRequested = true;
        stopReason = why;
        tipHalt = why;
        throw new Halt(why);
    }

    /** The step's result photos stored with the job (§22.4); a picture that cannot be stored is a warning. */
    private void storePhotos(DoseBlocksResult res) {
        if (res == null || res.photos.isEmpty()) {
            return;
        }
        try {
            photos.addAll(svc.storeResultPhotos(id, res));
        }
        catch (Exception e) {
            warnings.add("the result photos of the " + stepWords() + " step were not stored: "
                    + MachineEndpoints.rootMessage(e));
        }
    }

    /**
     * Check where it lands (§22.3): the locator dot at the band's centre (the engine moves it by minus E + trim, as every
     * dot), its new blob within ±R gives the coarse shift C; then the purge and the row of 8 moved by minus C, fitted:
     * T = E + trim + C + residual, saved when the fit holds (or asked about when the setup's own landing lies more than
     * 1.0 mm from it). Nothing is saved without a locator dot or a fit.
     */
    private void runLanding(DispenseConfig c) throws Exception {
        int steps = c.profile.netStepsPerDot;
        String surface = landingArea.surfaceName;
        LandingResult lr = new LandingResult();
        lr.jobId = id;
        lr.setupKey = setupKey;
        lr.needleSinceMs = needleSinceMs;
        lr.searchMm = searchMm;
        lr.expected = expected.clone();
        lr.headOffset = svc.headOffsetNow();
        begin(LOCATOR);
        DoseBlocksRequest r = landingRequest();
        DoseBlocksRequest.Block b = new DoseBlocksRequest.Block(steps, "locator", surface);
        b.xy.add(landingLayout.locator.clone());
        b.hopMm = DEFAULT_HOP_MM;
        b.matchRadiusMm = searchMm;
        r.blocks.add(b);
        r.lookXy = looks(b.xy);
        r.purgeXy = new ArrayList<>(); // the needle was primed until paste came out: no purge before the locator
        r.areaReserveMm = landingLayout.reservedMm; // the whole band, as a ladder reserves its rows
        DoseBlocksResult res = engine(() -> engine.runDoseBlocks(r), x -> x.run, x -> x.ok, x -> x.message);
        results.add(res);
        storePhotos(res);
        List<double[]> blobs = blobs(res);
        double[] coarse = LandingFit.locate(blobs, landingLayout.locator, searchMm);
        if (coarse == null) {
            lr.found = blobs.size();
            landing = lr;
            landingText = noLocatorText(c);
            done(LOCATOR, "no dot found within ±" + Texts.num(searchMm, 0) + " mm");
            skip(ROW, "no locator dot: nothing more was laid");
            landingEvent(lr, landingText);
            return;
        }
        lr.coarse = coarse;
        done(LOCATOR, String.format(Locale.ROOT, "the locator dot landed %s, %s mm from its spot", Views.signed(coarse[0]),
                Views.signed(coarse[1])));
        begin(ROW);
        double[] fov = fovMm();
        PadLadder.LandingLayout l = PadLadder.landingLayout(landingArea, searchMm, d95(steps), fov, coarse);
        if (l.problem != null) {
            landing = lr;
            landingText = l.problem;
            skip(ROW, l.problem);
            landingEvent(lr, landingText);
            return;
        }
        DoseBlocksRequest r2 = landingRequest();
        DoseBlocksRequest.Block row = new DoseBlocksRequest.Block(steps, "landing row", surface);
        row.xy.addAll(shifted(l.row, coarse));
        row.hopMm = LandingFit.ROW_MM[1];
        row.matchRadiusMm = LandingFit.MATCH_MM + LandingFit.RESIDUAL_MAX_MM;
        r2.blocks.add(row);
        r2.lookXy = looks(row.xy);
        r2.purgeXy = shifted(l.purge, coarse);
        DoseBlocksResult res2 = engine(() -> engine.runDoseBlocks(r2), x -> x.run, x -> x.ok, x -> x.message);
        results.add(res2);
        List<Photo> stored = new ArrayList<>(photos);
        storePhotos(res2);
        for (Photo p : photos) {
            if (!stored.contains(p) && lr.photoId == null) {
                lr.photoId = p.id;
            }
        }
        List<double[]> rowBlobs = blobs(res2);
        LandingFit.Fit fit = LandingFit.fit(l.row, rowBlobs);
        double[] trim = {c.needleXyTrim.dxMm, c.needleXyTrim.dyMm};
        lr.found = rowBlobs.size();
        lr.residual = new double[] {fit.dxMm, fit.dyMm};
        lr.n = fit.matched;
        lr.sdMm = fit.scatterMm;
        lr.pairs = fit.pairs;
        lr.dxMm = expected[0] + trim[0] + coarse[0] + fit.dxMm;
        lr.dyMm = expected[1] + trim[1] + coarse[1] + fit.dyMm;
        if (!fit.ok) {
            landing = lr;
            landingText = fit.matched >= LandingFit.MIN_MATCHES && fit.nextBest <= LandingFit.MAX_NEXT_BEST
                    ? String.format(Locale.ROOT, "The dots scatter %.2f mm around their spots (at most %.2f mm): check "
                            + "that the needle is on straight and tight, then check again. Look at the result photo.",
                            fit.scatterMm, LandingFit.SCATTER_MAX_MM)
                    : "Not enough dots near their spots (" + fit.matched + " of " + LandingFit.ROW_MM.length + "): check "
                            + "that the needle is on straight, then check again. Look at the result photo.";
            done(ROW, landingText);
            landingEvent(lr, landingText);
            return;
        }
        // a measurement, applied as the touch-off applies its own; the engine asks instead when it moved more than 1 mm
        try {
            svc.setLanding(lr, false, needleSinceMs);
        }
        catch (EngineRefusal e) {
            String changed = tipOrNeedleChanged();
            if (changed == null) {
                throw e;
            }
            synchronized (lock) {
                haltForTip(changed);
            }
        }
        landing = lr;
        landingText = lr.text;
        done(ROW, lr.saved ? "saved" : "not saved: asked whether the " + setupName + " is on");
    }

    /** A step of the landing check: every new blob kept, the job's setup and needle. */
    private DoseBlocksRequest landingRequest() {
        DoseBlocksRequest r = new DoseBlocksRequest();
        r.kind = DoseBlocksRequest.LADDER;
        r.landingCheck = true;
        r.parkAfter = false;
        stamp(r);
        return r;
    }

    /**
     * The frames a landing check's step looks through: the band's (the new blobs anywhere in it), and one more centred
     * on each spot that no band frame holds {@link PadLadder#TILE_INSET_MM} inside: the band's frames meet at their
     * edges, and the bare check before a dot needs its ring inside the frame.
     */
    private List<double[]> looks(List<double[]> spots) {
        double[] fov = fovMm();
        List<double[]> l = new ArrayList<>();
        for (double[] f : landingLayout.frames) {
            l.add(f.clone());
        }
        for (double[] p : spots) {
            boolean held = false;
            for (double[] f : l) {
                held |= Math.abs(p[0] - f[0]) <= fov[0] / 2 - PadLadder.TILE_INSET_MM
                        && Math.abs(p[1] - f[1]) <= fov[1] / 2 - PadLadder.TILE_INSET_MM;
            }
            if (!held) {
                l.add(p.clone());
            }
        }
        return l;
    }

    /** Spots moved by minus the coarse shift, so the dots land on them (§22.3). */
    private static List<double[]> shifted(List<double[]> spots, double[] coarse) {
        List<double[]> l = new ArrayList<>();
        for (double[] p : spots) {
            l.add(new double[] {p[0] - coarse[0], p[1] - coarse[1]});
        }
        return l;
    }

    /** The new blobs of a step as LandingFit takes them: {x, y, areaMm2}. */
    private static List<double[]> blobs(DoseBlocksResult res) {
        List<double[]> l = new ArrayList<>();
        for (DoseBlocksResult.Blob b : res.newBlobs) {
            l.add(new double[] {b.x, b.y, b.areaMm2});
        }
        return l;
    }

    /** §22.3's text when the locator found nothing within ±R. */
    private String noLocatorText(DispenseConfig c) {
        String fix;
        if (TipSetup.FORM_BENT.equals(c.tip.form)) {
            String dir = TipSetupEndpoints.direction(c.tip.bendToward);
            fix = dir == null ? "point the bend the way it points in its picture" : "point the bend toward " + dir
                    + ", as in its picture";
        }
        else {
            fix = "check that the needle is on straight";
        }
        return "No dot found within ±" + Texts.num(searchMm, 0) + " mm of where the " + c.tip.name + " should land: is "
                + "paste coming out (Prime until it does)? If it is, the needle lands farther off: " + fix
                + (searchMm < LandingFit.SEARCH_WIDE_MM - 1e-9 ? "; or search ±" + Texts.num(LandingFit.SEARCH_WIDE_MM, 0)
                        + " mm." : ".");
    }

    /** A TIP event of a landing check that saved nothing: what it found and why (§22.3). */
    private void landingEvent(LandingResult lr, String why) {
        try {
            svc.getDb().event(null, "WARN", DispenseService.TIP_EVENT, "landing check " + id + " of the " + setupName
                    + " saved nothing: " + why + " " + new Gson().toJson(lr));
        }
        catch (Exception e) {
            Logger.warn(e, "pad calibration {}: the landing event was not written", id);
        }
    }

    private void skip(String key, String text) {
        Step s = find(key);
        if (s != null) {
            s.state = "skipped";
            s.text = text;
        }
    }

    /** At most this long a step waits for OpenPnP's machine thread to be idle (its own Home or jog). */
    static final long MACHINE_IDLE_WAIT_MS = 10_000;

    /**
     * Before each engine step (rc1 review: only the start checked): OpenPnP homing or jogging by itself. A step queued
     * behind one of its tasks that fails would be dropped, so the step waits for the machine thread to be idle, and
     * the job stops when it stays busy.
     */
    private void waitMachineIdle() throws Exception {
        long until = System.currentTimeMillis() + MACHINE_IDLE_WAIT_MS;
        while (ctx.machine().isBusy()) {
            if (System.currentTimeMillis() > until) {
                throw new IllegalStateException("OpenPnP is busy (homing or moving): the " + stepWords() + " step was "
                        + "not started; do not use OpenPnP's own Home or jog while a calibration runs");
            }
            Thread.sleep(200);
        }
    }

    private SurfaceProbeRequest couponProbe(DispenseConfig c, CouponPlan k) {
        DispenseConfig.NamedLocation place = c.location(k.place);
        SurfaceProbeRequest r = new SurfaceProbeRequest();
        r.surfaceName = k.place;
        r.nominalZ = place.z;
        r.targets = WeighPlan.touches(k.rect);
        r.frameCheck = false;
        r.windowMm = c.padCalibration.couponWindowMm;
        r.coupon = new SurfaceProbeRequest.Coupon();
        r.coupon.label = k.label;
        r.coupon.rect = k.rect;
        r.coupon.looks = k.looks;
        CalibrationArea a = area != null ? area : c.calibrationArea.effective(c);
        if (a != null) {
            r.coupon.blankSpot = new double[] {a.x0Mm + a.widthMm / 2, a.y0Mm + a.heightMm / 2};
        }
        double room = Double.MAX_VALUE;
        for (double[] p : k.grid) {
            room = Math.min(room, Math.min(Math.min(p[0] - k.rect[0], k.rect[2] - p[0]), Math.min(p[1] - k.rect[1],
                    k.rect[3] - p[1])));
        }
        r.coupon.slackMm = Math.max(0, room - WeighPlan.insetMm(k.d95, c.padCalibration.couponMarginMm));
        return r;
    }

    /** The camera records of a block step (§7.4): one per block with the job's conditions (§1.7). */
    private List<DoseRecord> records(DoseBlocksResult res, String kind) throws Exception {
        DispenseConfig c = svc.getConfig();
        warnings.addAll(res.run.warnings);
        LadderStats.Settings set = DoseRecords.settings(c.padCalibration);
        List<DoseRecord> l = new ArrayList<>();
        for (int k = 0; k < res.blocks.size(); k++) {
            DoseBlocksResult.BlockOut b = res.blocks.get(k);
            DoseRecord r = DoseRecords.fromBlock(b, kind, set);
            conditions(r, c);
            r.method = manual != null ? "manual" : "auto";
            r.tipKey = res.setupKey != null ? res.setupKey : r.tipKey; // the setup the step ran with (§22.3)
            r.runUuid = res.run.runId;
            r.notes = b.label;
            r = svc.getDb().insertDoseRecord(r);
            recordIds.add(r.id);
            l.add(r);
        }
        synchronized (lock) {
            for (DoseBlocksResult.Crop cr : res.crops) {
                String cid = (DoseRecord.COUPON.equals(kind) ? "w" : "l") + cr.id;
                crops.put(cid, cr);
                cropSteps.put(cid, res.blocks.get(cr.block).netSteps);
            }
        }
        svc.getDb().event(res.run.runId, "INFO", "PCAL", new Gson().toJson(Views.map("job", id, "step",
                DoseRecord.COUPON.equals(kind) ? WEIGH : LADDER, "records", ids(l))));
        evidence = currentEvidence();
        return l;
    }

    private static List<Long> ids(List<DoseRecord> l) {
        List<Long> out = new ArrayList<>();
        for (DoseRecord r : l) {
            out.add(r.id);
        }
        return out;
    }

    /** The setup a record belongs to (§1.7). */
    private void conditions(DoseRecord r, DispenseConfig c) {
        r.pressurePsi = c.profile.pressurePsi;
        r.doseFp = c.profile.doseFingerprint();
        r.tipKey = setupKey != null ? setupKey : c.tip.key;
        r.pasteKey = c.paste.key;
        r.regulatorPsi = req.regulatorPsi;
        r.regulatorAtMs = req.regulatorAtMs;
        r.pasteLifeAtMs = req.pasteLifeAtMs;
        r.controllerConnectedAtMs = svc.getControllerConnectedAtMs();
        r.startAngleSetAtMs = c.profile.startAngleSetAtMs;
        r.startAngleDeg = c.profile.startAngleDeg;
        r.simulated = svc.isSimulatorConfirmed();
        r.jobId = id;
        r.operator = req.operator;
    }

    /**
     * The control (§7.4): this job's profile-dose dots against the bench's blank area and the newest
     * earlier fresh passing record of the profile dose. Outside: the job stops before the coupons.
     */
    private void control(DispenseConfig c, List<DoseRecord> written) throws Exception {
        int s = c.profile.netStepsPerDot;
        DoseRecord mine = null;
        for (DoseRecord r : written) {
            if (r.steps == s && mine == null) {
                mine = r;
            }
        }
        if (mine == null) {
            return;
        }
        controlMedianMm2 = DoseRecords.medianArea(mine);
        if (controlMedianMm2 == null) {
            controlPass = false;
            controlText = "the control " + DoseModel.turnName(s) + " dots were not found by the camera: check the "
                    + "needle, the regulator (" + Texts.num(c.profile.pressurePsi, 0) + " psi) and the paste before "
                    + "calibrating";
            return;
        }
        Double earlier = null;
        long now = System.currentTimeMillis();
        DoseRecord.Epochs ep = epochs();
        if (tip.benchModel) {
            for (DoseRecord r : svc.getDb().doseRecords(s, now - (long) (c.padCalibration.doseRecordMaxAgeH
                    * 3600e3))) {
                if (r.id != mine.id && !id.equals(r.jobId) && DoseRecord.OPTICAL.equals(r.kind)
                        && Boolean.TRUE.equals(r.pass) && r.isFresh(now, c, ep)) {
                    earlier = DoseRecords.medianArea(r);
                }
            }
        }
        else {
            earlier = tableArea(s); // §22.2: the setup's own dot table, any age, without the bench
        }
        LadderStats.Check k = LadderStats.control(s, controlMedianMm2, earlier, c.profile.pressurePsi,
                DoseRecords.settings(c.padCalibration), tip);
        controlPass = k.ok;
        controlText = k.text;
    }

    /** The median dot area of the record the tip's dot table holds for a dose (as the job started), or null. */
    private Double tableArea(int s) throws Exception {
        Tip.Measured m = tip.benchModel || tip.measured == null ? null : tip.measured.get(s);
        DoseRecord r = m == null ? null : svc.getDb().doseRecord(m.recordId);
        return r == null ? null : DoseRecords.medianArea(r);
    }

    private DoseRecord.Epochs epochs() {
        return new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
    }

    /**
     * §7.2: when the ladder changed a group's choice (its plan failed and an alternative would pass with a
     * confirmed mass), the weighing weighs the new choice, on a coupon whose checked margin holds its grid: the
     * coupon step looked at each coupon around its planned grid, so a coupon whose new grid would reach past that
     * (or not fit at all) weighs its planned dose, and the new one waits for another calibration.
     */
    private void reweigh(DispenseConfig c) throws Exception {
        if (manual != null || measure != null || coupons.isEmpty()) {
            return;
        }
        evidence = currentEvidence();
        boolean changed = false;
        for (GroupPlan g : groups) {
            Decider.Decision d = decide(g, true);
            if (d != null && d.replaced && d.steps != g.weighSteps) {
                g.weighSteps = d.steps;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        Map<Integer, Integer> need = new TreeMap<>();
        for (GroupPlan g : groups) {
            if (!massConfirmed(g.weighSteps)) {
                need.merge(g.weighSteps, 1, Integer::sum);
            }
        }
        List<WeighPlan.Need> l = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : need.entrySet()) {
            l.add(new WeighPlan.Need(e.getKey(), e.getValue()));
        }
        List<String> places = new ArrayList<>();
        for (CouponPlan k : coupons) {
            places.add(k.place);
        }
        WeighPlan.Allocation a = WeighPlan.allocate(l, c.profile.netStepsPerDot,
                massConfirmed(c.profile.netStepsPerDot), places.size());
        double margin = c.padCalibration.couponMarginMm;
        List<String> kept = new ArrayList<>();
        synchronized (lock) {
            List<CouponPlan> checked = new ArrayList<>(coupons);
            coupons.clear();
            notWeighed.clear();
            notWeighed.addAll(a.notWeighed);
            for (int k = 0; k < a.coupons.size(); k++) {
                CouponPlan was = checked.get(k);
                CouponPlan now = a.coupons.get(k) == was.steps ? was : coupon(c, places.get(k), a.coupons.get(k));
                if (now != was && !WeighPlan.within(now.grid, WeighPlan.insetMm(now.d95, margin), was.grid,
                        WeighPlan.insetMm(was.d95, margin))) {
                    kept.add(String.format(Locale.ROOT, "the %s weigh dots do not fit where the camera checked %s: it "
                            + "weighs the %s as planned; Calibrate again weighs the %s", DoseModel.turnName(now.steps),
                            was.place, DoseModel.turnNoun(was.steps), DoseModel.turnNoun(now.steps)));
                    now = was;
                }
                coupons.add(now);
            }
            for (int s : a.coupons) {
                boolean weighed = false;
                for (CouponPlan k : coupons) {
                    weighed |= k.steps == s;
                }
                if (!weighed && !notWeighed.contains(s)) {
                    notWeighed.add(s);
                }
            }
            java.util.Collections.sort(notWeighed);
        }
        warnings.add("the ladder changed a group's dose: the weighing weighs " + couponWords());
        warnings.addAll(kept);
    }

    /** A connected balance (the SIM's, §11.6): the job reads both values itself; the emulated human wipes. */
    private void readBalance() throws Exception {
        List<Reading> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            Balance.Reading[] r;
            if (balance instanceof SimBalance) {
                r = ((SimBalance) balance).weighWipeWeigh(k.place, READ_TIMEOUT_MS);
            }
            else {
                throw new IllegalStateException("the balance " + balance.kind() + " cannot load a coupon by itself");
            }
            l.add(new Reading(k.place, r[0].mg / 1000, r[1].mg / 1000));
        }
        synchronized (lock) {
            saveReadings(l, true, "sim");
        }
    }

    /** One coupon's two readings in grams (with paste, wiped). */
    static final class Reading {
        final String place;
        final double fullG;
        final double emptyG;

        Reading(String place, double fullG, double emptyG) {
            this.place = place;
            this.fullG = fullG;
            this.emptyG = emptyG;
        }
    }

    /**
     * The operator's readings (§7.5): checked (plausible, not late) before anything is written, then a
     * mass record per coupon; the decision follows on the job's thread.
     */
    void answer(List<Reading> readings, boolean confirm, boolean skip) throws Exception {
        synchronized (lock) {
            if (!AWAITING.equals(state)) {
                throw ApiException.refused("the pad calibration is not waiting for readings (it is " + state + ")");
            }
            if (skip) {
                for (CouponPlan k : coupons) {
                    if (k.record != null && k.mass == null) {
                        k.mass = skipRow(svc, k.record, req.operator, id);
                    }
                }
                state = DECIDING;
                Step r = find("readings");
                r.state = "skipped";
                r.text = "weighing skipped: the types stay provisional";
            }
            else {
                saveReadings(readings, confirm, "typed");
                state = DECIDING;
            }
        }
        ctx.padCalJobs.later(() -> {
            try {
                finish();
            }
            catch (Halt h) {
                halted();
            }
            catch (Throwable e) {
                fail(e);
            }
        });
    }

    private void saveReadings(List<Reading> readings, boolean confirm, String method) throws Exception {
        List<CouponPlan> order = new ArrayList<>();
        for (Reading r : readings) {
            CouponPlan k = null;
            for (CouponPlan x : coupons) {
                if (x.place.equals(r.place)) {
                    k = x;
                }
            }
            if (k == null || k.record == null) {
                throw ApiException.badRequest("no weigh coupon '" + r.place + "' in this calibration");
            }
            if (order.contains(k)) {
                // one mass row per coupon: the second would be refused after the first was written
                throw ApiException.badRequest(r.place + " is typed twice: type each coupon's two readings once");
            }
            order.add(k);
        }
        for (CouponPlan k : coupons) {
            if (k.record != null && k.mass == null && !order.contains(k)) {
                throw ApiException.badRequest("type both readings of " + k.place + " too (or skip the weighing)");
            }
        }
        for (int i = 0; i < readings.size(); i++) {
            check(svc, order.get(i).record, readings.get(i).fullG, readings.get(i).emptyG, confirm);
        }
        for (int i = 0; i < readings.size(); i++) {
            CouponPlan k = order.get(i);
            k.mass = massRow(svc, k.record, readings.get(i).fullG, readings.get(i).emptyG, method, req.operator, id);
            recordIds.add(k.mass.id);
        }
        Step s = find("readings");
        s.state = "done";
        s.text = readingWords();
    }

    /**
     * §7.5 before a reading is written: both readings in grams with the wiped one smaller; a reading late
     * after its dots (the flux dries) or far from the expected mass needs {@code confirm}.
     */
    static void check(DispenseService svc, DoseRecord coupon, double fullG, double emptyG, boolean confirm)
            throws ApiException {
        DispenseConfig c = svc.getConfig();
        if (!(fullG > 0) || !(emptyG > 0) || !Double.isFinite(fullG) || !Double.isFinite(emptyG)) {
            throw ApiException.badRequest("type both readings of " + coupon.couponPlace + " in grams");
        }
        if (fullG <= emptyG) {
            throw ApiException.badRequest(String.format(Locale.ROOT, "%s: the coupon with paste (%.3f g) must weigh "
                    + "more than wiped (%.3f g): was the wiped coupon weighed second?", coupon.couponPlace, fullG,
                    emptyG));
        }
        long now = System.currentTimeMillis();
        String stale = coupon == null ? null : staleAsMass(coupon, now, c, svc);
        if (stale != null) {
            throw ApiException.refused("the dots on " + coupon.couponPlace + " are no evidence any more ("
                    + stale + "): skip the reading and weigh again");
        }
        if (confirm) {
            return;
        }
        double minutes = (now - coupon.createdAtMs) / 60000.0;
        if (minutes > c.padCalibration.readingConfirmAfterMin) {
            throw new ApiException(409, "late", String.format(Locale.ROOT, "The coupon waited %.0f min: the flux "
                    + "dries, so the reading may be low. Save it anyway?", minutes)).with("coupon", coupon.couponPlace);
        }
        double mg = (fullG - emptyG) * 1000;
        int found = coupon.found != null && coupon.found > 0 ? coupon.found : coupon.n;
        Tip tip = mountedTip(svc);
        double perDot = countMgPerDot(tip, coupon.steps);
        double expected = found * perDot;
        // §22.2: a tip never weighed at this recipe is counted with the bench's mass: plausible at 0.1 to 10 times it
        boolean plausible = tip.benchModel || tip.mgPerStep != null ? LadderStats.plausible(mg, found, perDot)
                : mg >= 0.1 * expected && mg <= 10 * expected;
        if (!plausible) {
            throw new ApiException(409, "implausible", String.format(Locale.ROOT, "You typed %.3f g − %.3f g = %.0f mg; "
                    + "about %.0f mg was expected for %d %s dots. Check the unit and that the wiped coupon was weighed "
                    + "second, then save again to keep it.", fullG, emptyG, mg, expected, found,
                    DoseModel.turnName(coupon.steps))).with("coupon", coupon.couponPlace);
        }
    }

    /** The mounted tip with its dot table; the configuration's tip without one when the records cannot be read. */
    static Tip mountedTip(DispenseService svc) {
        try {
            return svc.mountedTip();
        }
        catch (java.sql.SQLException e) {
            DispenseConfig c = svc.getConfig();
            Tip t = new Tip(c.tip.key, c.tip.name, c.tip.idMm, c.tip.odMm, c.tip.minDotSteps);
            t.benchModel = TipSetup.MODEL_BENCH.equals(c.tip.model);
            return t;
        }
    }

    private static String staleAsMass(DoseRecord coupon, long now, DispenseConfig c, DispenseService svc) {
        DoseRecord.Epochs ep = new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
        if (DispenseService.weighable(coupon, now, c, ep)) {
            return null;
        }
        DoseRecord m = new DoseRecord();
        m.kind = DoseRecord.MASS;
        m.createdAtMs = coupon.createdAtMs;
        m.steps = coupon.steps;
        m.doseFp = coupon.doseFp;
        m.regulatorPsi = coupon.regulatorPsi;
        m.tipKey = coupon.tipKey;
        m.pasteKey = coupon.pasteKey;
        m.simulated = coupon.simulated;
        return m.whyStale(now, c, ep);
    }

    /**
     * The mass record of a coupon's readings (§7.5), confirmed by the newest fresh reading it agrees with. Per
     * dot is per dot laid: every screw turn pushes its paste out, and a dot the camera missed left it on the tip
     * for the next one.
     */
    static DoseRecord massRow(DispenseService svc, DoseRecord coupon, double fullG, double emptyG, String method,
            String operator, String jobId) throws Exception {
        DispenseConfig c = svc.getConfig();
        DispenseDb db = svc.getDb();
        DoseRecord m = massBase(coupon, method, operator, jobId);
        m.fullMg = round3(fullG * 1000);
        m.emptyMg = round3(emptyG * 1000);
        m.totalMg = round3(m.fullMg - m.emptyMg);
        m.mgPerDot = m.totalMg / coupon.n;
        m.mgPerStep = m.mgPerDot / coupon.steps;
        m.mgSe = Math.sqrt(2) * ManualBalance.RESOLUTION_MG / coupon.n;
        long now = System.currentTimeMillis();
        m.weighDelayS = (int) Math.max(0, (now - coupon.createdAtMs) / 1000);
        DoseRecord.Epochs ep = new DoseRecord.Epochs(svc.getControllerConnectedAtMs(), svc.isSimulatorConfirmed());
        Long by = null;
        for (DoseRecord r : db.doseRecords(null, now - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3))) {
            if (r.hasReading() && r.isFresh(now, c, ep) && r.mgPerStep != null
                    && Math.abs(m.mgPerStep / r.mgPerStep - 1) <= c.padCalibration.confirmTolerance + Limits.EPS) {
                by = r.id;
            }
        }
        m.confirmedBy = by;
        return db.insertDoseRecord(m);
    }

    /** A weighing skipped by the operator: a mass row without readings, so the coupon is free again. */
    static DoseRecord skipRow(DispenseService svc, DoseRecord coupon, String operator, String jobId) throws Exception {
        DoseRecord m = massBase(coupon, "typed", operator, jobId);
        m.why = "the weighing was skipped";
        return svc.getDb().insertDoseRecord(m);
    }

    private static DoseRecord massBase(DoseRecord coupon, String method, String operator, String jobId) {
        DoseRecord m = new DoseRecord();
        m.kind = DoseRecord.MASS;
        m.steps = coupon.steps;
        m.n = coupon.n;
        m.couponRecordId = coupon.id;
        m.couponPlace = coupon.couponPlace;
        m.pressurePsi = coupon.pressurePsi;
        m.doseFp = coupon.doseFp;
        m.tipKey = coupon.tipKey;
        m.pasteKey = coupon.pasteKey;
        m.regulatorPsi = coupon.regulatorPsi;
        m.regulatorAtMs = coupon.regulatorAtMs;
        m.pasteLifeAtMs = coupon.pasteLifeAtMs;
        m.controllerConnectedAtMs = coupon.controllerConnectedAtMs;
        m.startAngleSetAtMs = coupon.startAngleSetAtMs;
        m.startAngleDeg = coupon.startAngleDeg;
        m.simulated = coupon.simulated;
        m.method = "sim".equals(method) && !coupon.simulated ? "typed" : method;
        m.runUuid = coupon.runUuid;
        m.jobId = jobId != null ? jobId : coupon.jobId;
        m.operator = operator;
        return m;
    }

    private static double round3(double x) {
        return Math.round(x * 1000) / 1000.0;
    }

    /** Steps 6 and 7 (§7.6, §7.7): decide every group on the pooled fresh records, write, plan again. */
    private void finish() throws Exception {
        if (manual != null || measure != null) {
            evidence = currentEvidence();
            Step r = find("readings");
            if (r != null && "now".equals(r.state)) {
                r.state = "done";
            }
            end(DONE, null);
            return;
        }
        synchronized (lock) {
            if (stopRequested) {
                throw new Halt(stopReason);
            }
            String changed = tipOrNeedleChanged();
            if (changed != null) {
                haltForTip(changed); // nothing is written for another setup or needle (§22.3)
            }
            state = DECIDING;
        }
        begin("decide");
        evidence = currentEvidence();
        DispenseConfig c = svc.getConfig();
        if (controlPass == null) {
            control(c);
        }
        int characterized = 0;
        for (GroupPlan g : groups) {
            try {
                write(c, g);
            }
            catch (EngineRefusal e) {
                // a mount or a needle change since the start (the engine checks it under its lock, §22.3)
                if (!"tip-changed".equals(e.code) && !"needle-changed".equals(e.code)) {
                    throw e;
                }
                synchronized (lock) {
                    haltForTip(e.getMessage());
                }
            }
            characterized += PadCalibrationRecord.CHARACTERIZED.equals(g.outcome) ? 1 : 0;
        }
        done("decide", characterized + " of " + groups.size() + " characterized");
        Step p = find("plan");
        if (entry != null) {
            synchronized (lock) {
                state = PLANNING;
            }
            begin("plan");
            try {
                newPlanId = PlanAgain.of(ctx, dispense, entry).plan.planId;
                done("plan", "planned again");
            }
            catch (Exception e) {
                planError = "Plan again: " + MachineEndpoints.rootMessage(e);
                p.state = "failed";
                p.text = planError;
            }
        }
        end(DONE, null);
    }

    /** The pooled evidence now (§7.6), from the source's check inputs (the records written since included). */
    private Map<Integer, DoseEvidence> currentEvidence() throws Exception {
        return inputs().evidence;
    }

    /** The check inputs of the source as it is now (the saved types, the pooled fresh records). */
    private CheckInputs inputs() throws Exception {
        if (entry != null) {
            return PadProfileViews.inputs(svc, entry.plan);
        }
        if (boardFile != null) {
            return profile.checked(svc, null, boardFile).in;
        }
        return PadInstances.inputs(svc, new ArrayList<>(), false);
    }

    /** The control from the newest fresh record of the profile dose, when this job laid none (Finish). */
    private void control(DispenseConfig c) throws Exception {
        int s = c.profile.netStepsPerDot;
        long now = System.currentTimeMillis();
        DoseRecord newest = null;
        DoseRecord.Epochs ep = epochs();
        for (DoseRecord r : svc.getDb().doseRecords(s, now - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3))) {
            if (DoseRecord.OPTICAL.equals(r.kind) && r.isFresh(now, c, ep)) {
                newest = r;
            }
        }
        Double m = newest == null ? null : DoseRecords.medianArea(newest);
        if (m == null) {
            controlPass = false;
            controlText = "no fresh control dots of the " + DoseModel.turnNoun(s) + " on the blank: calibrate again";
            return;
        }
        controlMedianMm2 = m;
        LadderStats.Check k = LadderStats.control(s, m, tip.benchModel ? null : tableArea(s), c.profile.pressurePsi,
                DoseRecords.settings(c.padCalibration), tip);
        controlPass = k.ok;
        controlText = k.text;
    }

    /**
     * The decision of a group (§7.6). {@code hypothetical}: as if every dose with camera results had a
     * confirmed mass (the weighing is still to come: which dose should it weigh?).
     */
    private Decider.Decision decide(GroupPlan g, boolean hypothetical) throws Exception {
        CheckInputs in = inputs();
        if (hypothetical) {
            for (DoseEvidence e : in.evidence.values()) {
                if (e.opticalFresh) {
                    e.massConfirmed = true;
                }
            }
        }
        Decider.Inputs d = new Decider.Inputs();
        d.check = in;
        d.group = g.group;
        d.steps = g.steps;
        d.perPad = g.perPad;
        d.edited = g.edited;
        d.controlPass = hypothetical || Boolean.TRUE.equals(controlPass);
        d.controlText = controlText;
        d.regulatorAttested = true;
        d.recordsTipKey = null;
        d.validatedNamed = req.demotes.contains(g.typeKey);
        d.settings = DoseRecords.settings(svc.getConfig().padCalibration);
        try {
            return Decider.decide(d);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** §7.7: the pad_calibrations row of a group and, when measured, its new type version. */
    private void write(DispenseConfig c, GroupPlan g) throws Exception {
        Decider.Decision d = decide(g, false);
        g.decision = d;
        PadType cur = g.typeKey == null ? null : svc.getDb().currentPadType(g.typeKey, setupKey);
        PadCalibrationRecord rec = new PadCalibrationRecord();
        rec.jobId = id;
        rec.typeKey = g.typeKey != null ? g.typeKey : g.group;
        // a stand-in (no version of this setup yet) was calibrated from nothing (§22.2)
        rec.versionFrom = cur == null || cur.standIn ? null : cur.version;
        rec.setupKey = setupKey;
        rec.needleSinceMs = needleSinceMs;
        rec.runs = new ArrayList<>(runs);
        rec.simulated = svc.isSimulatorConfirmed();
        rec.operator = req.operator;
        if (d == null || cur == null) {
            rec.outcome = PadCalibrationRecord.FAILED;
            rec.message = g.label + ": " + (cur == null ? "its pad type " + g.typeKey + " is gone"
                    : "its pads are not on the board any more");
            rec.checks.add(new PadCalibrationRecord.Check("group", false, rec.message));
            g.outcome = rec.outcome;
            g.text = rec.message;
            g.record = svc.applyPadCalibration(rec, null);
            return;
        }
        rec.steps = d.steps;
        rec.dotsPerPad = d.perPad;
        rec.massPerPadMg = d.massPerPadMg;
        for (Decider.Check k : d.checks) {
            PadCalibrationRecord.Check x = new PadCalibrationRecord.Check(k.key, k.ok, k.ok
                    ? (k.measured != null ? k.measured : "ok") : k.text);
            x.predicted = k.predicted;
            x.measured = k.measured;
            x.limit = k.limit;
            rec.checks.add(x);
        }
        long now = System.currentTimeMillis();
        List<DoseRecord> fresh = DoseRecords.fresh(svc.getDb().doseRecords(null, now
                - (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3)), c, epochs(), now, svc.getDb().flaggedJobs());
        rec.evidence = DoseRecords.ids(fresh, d.steps);
        String turn = DoseModel.turnName(d.steps);
        String dots = Texts.plural(d.perPad, turn + " dot", turn + " dots");
        PadType v = null;
        if (Decider.CHARACTERIZED.equals(d.outcome)) {
            rec.outcome = PadCalibrationRecord.CHARACTERIZED;
            v = cur.copy();
            v.status = PadTypeStatus.CHARACTERIZED;
            plan(v, d, c, g);
            v.doseFp = c.profile.doseFingerprint();
            v.massPerPadMg = d.massPerPadMg;
            v.origin = PadType.ORIGIN_AUTO_CALIBRATED;
            v.notes = String.format(Locale.ROOT, "Calibrated automatically %s on the blank and %s: %s per pad, about "
                    + "%s mg per pad (target %s mg). Job %s.", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                            .format(new Date()), couponCountWords(), dots, Texts.mg(d.massPerPadMg),
                    Texts.mg(d.targetMg), id);
            g.text = String.format(Locale.ROOT, "%s gets %s per pad, about %s mg per pad (%s)", g.label, dots,
                    Texts.mg(d.massPerPadMg), Texts.vsTarget(d.errorPct / 100.0, d.targetMg));
            if (d.replacedText != null) {
                g.text += "; " + d.replacedText;
            }
        }
        else {
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            boolean moved = cur.dotSteps == null || cur.dotSteps != d.steps || cur.dotsPerPad == null
                    || cur.dotsPerPad != d.perPad;
            // what was measured goes on a type below the gate; a failed calibration never demotes one above it
            if (moved && !cur.status.atLeast(PadTypeStatus.CHARACTERIZED)) {
                v = cur.copy();
                plan(v, d, c, g);
                v.doseFp = null;
                v.massPerPadMg = null;
                v.notes = "Measured " + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date())
                        + " (job " + id + "): " + dots + " per pad; not characterized: " + d.why;
            }
            String why = d.why;
            if (notWeighed.contains(d.steps)) {
                why = "not weighed in this job (" + Texts.plural(coupons.size(), "coupon", "coupons")
                        + "): Calibrate again weighs the " + DoseModel.turnNoun(d.steps);
            }
            g.text = g.label + " stays provisional: " + why;
        }
        rec.message = g.text;
        g.outcome = rec.outcome;
        g.record = svc.applyPadCalibration(rec, v);
    }

    private void plan(PadType v, Decider.Decision d, DispenseConfig c, GroupPlan g) {
        v.dotSteps = d.steps;
        v.dotsPerPad = d.perPad;
        v.tipKey = setupKey;
        v.calAreaMm2 = g.areaMm2 > 0 ? g.areaMm2 : v.calAreaMm2;
    }

    // ------------------------------------------------------------------------------------------
    // STOP, cancel, the end
    // ------------------------------------------------------------------------------------------

    /** STOP (§7.8): under the job's lock, so no further engine step is queued after it. */
    void stop(String reason) {
        synchronized (lock) {
            if (ended()) {
                return;
            }
            stopRequested = true;
            stopReason = reason;
            if (AWAITING.equals(state)) {
                end(STOPPED, reason);
            }
        }
    }

    /** Cancel: the engine step is aborted as by STOP; at the readings the job ends and nothing moves. */
    void cancel() {
        synchronized (lock) {
            if (ended()) {
                return;
            }
            cancelRequested = true;
            stopRequested = true;
            stopReason = "Cancel pressed: the pad calibration was cancelled";
            if (AWAITING.equals(state)) {
                end(CANCELLED, stopReason);
                return;
            }
        }
        (engine != null ? engine : ctx.padCalJobs.engine(svc)).abort(stopReason);
    }

    private void halted() {
        end(cancelRequested ? CANCELLED : STOPPED, stopReason);
    }

    /**
     * A needle change is about to be recorded (§22.3): a job that runs, decides or plans stops at its next engine step
     * (the change aborts the one that runs) with the needle's words as its result, nothing saved.
     */
    void needleChanging(long atMs) {
        synchronized (lock) {
            if (ended() || AWAITING.equals(state)) {
                return;
            }
            tipHalt = needleChangedDuring(atMs);
            stopRequested = true;
            stopReason = tipHalt;
        }
    }

    /**
     * A needle change was recorded (§22.3): a job waiting for the readings of its coupons ends stopped (the engine wrote
     * their skip rows: their dots were laid by the needle that came off). A job between its engine steps was stopped
     * before the change ({@link PadCalibrationJobs#needleChanging}).
     */
    void needleChanged(NeedleChange nc) {
        synchronized (lock) {
            if (ended() || !AWAITING.equals(state)) {
                return;
            }
            List<String> places = new ArrayList<>();
            for (CouponPlan k : coupons) {
                if (k.record != null && k.mass == null) {
                    places.add(k.place);
                }
            }
            voidedBy = nc;
            stopRequested = true;
            String why = "The needle was changed at " + ZCheck.clock(nc.atMs) + ": the dots on "
                    + (places.isEmpty() ? "the coupons" : String.join(" and ", places)) + " no longer count. Wipe "
                    + (places.size() == 1 ? "it" : "them") + "; the types stay as they were.";
            stopReason = why;
            tipHalt = why;
            end(STOPPED, why);
        }
    }

    /** The operator saved the landing the check asked about (POST /api/needle/landing {jobId, accept}). */
    void landingAccepted(DispenseConfig.NeedleLanding l) {
        synchronized (lock) {
            landingText = landing == null ? null : landing.text;
            if (result != null) {
                result.put("landing", landingView());
                result.put("sentence", landingText);
                result.put("outcome", "measured");
            }
        }
    }

    /**
     * Ends the job FAILED, which releases its lock. An Error (a bug, out of memory) fails it as an exception does,
     * logged with its stack, and named in plain words.
     */
    private void fail(Throwable e) {
        String why = e instanceof ApiException ? e.getMessage()
                : e instanceof Exception ? MachineEndpoints.rootMessage(e)
                : "an internal error stopped the calibration (" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "); see the OpenPnP log";
        if (e instanceof Exception) {
            Logger.warn("pad calibration {} failed: {}", id, why);
        }
        else {
            Logger.error(e, "pad calibration {} failed", id);
        }
        failure = why;
        end(FAILED, why);
    }

    private void end(String outcome, String why) {
        synchronized (lock) {
            if (ended()) {
                return;
            }
            state = outcome;
            endedAtMs = System.currentTimeMillis();
            Step s = step == null ? null : find(step);
            if (s != null && "now".equals(s.state)) {
                s.state = DONE.equals(outcome) ? "done" : FAILED.equals(outcome) ? "failed" : "stopped";
                if (!DONE.equals(outcome)) {
                    s.text = why;
                }
            }
            result = resultView(outcome, why);
        }
        try {
            svc.getDb().event(null, DONE.equals(outcome) ? "INFO" : "WARN", "PCAL", new Gson().toJson(Views.map("job",
                    id, "state", outcome, "sentence", result.get("sentence"))));
            if (manual == null && measure == null && !DONE.equals(outcome)) {
                for (GroupPlan g : groups) {
                    if (g.record == null && g.typeKey != null) {
                        PadCalibrationRecord rec = new PadCalibrationRecord();
                        rec.jobId = id;
                        rec.typeKey = g.typeKey;
                        rec.setupKey = setupKey;
                        rec.needleSinceMs = needleSinceMs;
                        rec.outcome = FAILED.equals(outcome) ? PadCalibrationRecord.FAILED
                                : CANCELLED.equals(outcome) ? PadCalibrationRecord.CANCELLED
                                        : PadCalibrationRecord.STOPPED;
                        rec.message = String.valueOf(result.get("sentence"));
                        rec.runs = new ArrayList<>(runs);
                        rec.evidence = new ArrayList<>(recordIds);
                        rec.simulated = svc.isSimulatorConfirmed();
                        rec.operator = req.operator;
                        g.record = svc.applyPadCalibration(rec, null);
                    }
                }
            }
        }
        catch (Exception e) {
            Logger.warn(e, "pad calibration {}: the end was not recorded", id);
        }
    }

    boolean ended() {
        return DONE.equals(state) || STOPPED.equals(state) || CANCELLED.equals(state) || FAILED.equals(state);
    }

    /** Running, deciding or planning: other dispense tasks wait (§7.1). */
    boolean holdsLock() {
        return RUNNING.equals(state) || DECIDING.equals(state) || PLANNING.equals(state);
    }

    String stepWords() {
        return step == null ? "starting" : step;
    }

    private void begin(String key) {
        step = key;
        Step s = find(key);
        if (s != null) {
            s.state = "now";
        }
    }

    private void done(String key, String text) {
        Step s = find(key);
        if (s != null) {
            s.state = "done";
            s.text = text;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Words
    // ------------------------------------------------------------------------------------------

    private String couponWords() {
        List<String> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            l.add(k.place);
        }
        return String.join(" and ", l);
    }

    private String couponCountWords() {
        return coupons.isEmpty() ? "no weigh coupons" : coupons.size() == 1 ? "one weigh coupon"
                : coupons.size() + " weigh coupons";
    }

    private String weighWords() {
        List<String> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            l.add(k.dots + " " + DoseModel.turnName(k.steps) + " dots on " + k.place);
        }
        return String.join(", ", l);
    }

    private String ladderWords(DoseBlocksResult res) throws Exception {
        int dots = 0;
        List<String> per = new ArrayList<>();
        Map<Integer, DoseEvidence> ev = currentEvidence();
        Set<Integer> seen = new LinkedHashSet<>();
        int minHops = svc.getConfig().padCalibration.minHops;
        for (DoseBlocksResult.BlockOut b : res.blocks) {
            dots += b.dots.size();
            if (seen.add(b.netSteps)) {
                DoseEvidence e = ev.get(b.netSteps);
                // the weigh dots still to come add their moves to the thread count
                boolean weighed = false;
                for (CouponPlan k : coupons) {
                    weighed |= k.steps == b.netSteps;
                }
                boolean ok = e != null && (e.opticalPass || weighed && e.hops < minHops && e.failures.size() == 1);
                per.add(DoseModel.turnNoun(b.netSteps).replace(" turn", "") + (ok ? " ok" : " failed"));
            }
        }
        return dots + " dots: " + String.join(", ", per);
    }

    private String readingWords() {
        List<String> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            if (k.mass != null && k.mass.totalMg != null) {
                l.add(String.format(Locale.ROOT, "%s %s mg (%s mg per %s dot)", k.place, Texts.num(k.mass.totalMg, 0),
                        Texts.num(k.mass.mgPerDot, 3), DoseModel.turnName(k.steps)));
            }
        }
        return l.isEmpty() ? "no readings" : String.join(", ", l);
    }

    // ------------------------------------------------------------------------------------------
    // Views (§7.9, §7.10, §12.3)
    // ------------------------------------------------------------------------------------------

    /** The before-start summary (§7.9). */
    Map<String, Object> preview() {
        DispenseConfig c = svc.getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> st = new ArrayList<>();
        for (Step s : steps) {
            st.add(Views.map("key", s.key, "state", "skipped".equals(s.state) ? "skipped" : "todo", "text", s.text,
                    "estimatedSeconds", Math.round(s.estimateS)));
        }
        m.put("steps", st);
        m.put("estimatedSeconds", Math.round(estimatedSeconds));
        m.put("pasteMg", Math.round(pasteMg));
        m.put("measure", measure);
        m.put("forTip", setupKey);
        m.put("tipName", setupName);
        m.put("landing", landingPreview());
        if (MEASURE_LANDING.equals(measure) && landingLayout != null && landingLayout.problem == null) {
            double left = area.leftMm();
            m.put("blank", Views.map("surfaceName", area.surfaceName, "x0", r1(landingLayout.x0), "x1",
                    r1(landingLayout.x1), "y0", r1(landingLayout.y0), "y1", r1(landingLayout.y1), "usesMm",
                    r1(landingLayout.reservedMm), "leftMm", r1(left), "leftAfterMm", r1(Math.max(0, left
                            - landingLayout.reservedMm)), "heightMm", r1(area.heightMm()), "probe", blankNeeded,
                    "probeWhy", blankWhy));
        }
        else if (layout != null) {
            double left = area.leftMm();
            m.put("blank", Views.map("surfaceName", area.surfaceName, "x0", r1(layout.x0), "x1", r1(layout.x1), "y0",
                    r1(layout.y0), "y1", r1(layout.y1), "usesMm", r1(layout.reservedMm), "leftMm", r1(left),
                    "leftAfterMm", r1(Math.max(0, left - layout.reservedMm)), "heightMm", r1(area.heightMm()),
                    "probe", blankNeeded, "probeWhy", blankWhy));
        }
        else {
            m.put("blank", null);
        }
        List<Map<String, Object>> ld = new ArrayList<>();
        for (int i = 0; i < ladderDoses.size(); i++) {
            PadLadder.Dose d = ladderDoses.get(i);
            ld.add(Views.map("steps", d.steps, "turn", DoseModel.turnNoun(d.steps), "dots", d.dots, "label", d.label,
                    "pitchMm", layout == null ? null : r2(PadLadder.pitch(d.hopMm, d.d95Mm))));
        }
        m.put("ladder", ld);
        List<Map<String, Object>> cp = new ArrayList<>();
        for (CouponPlan k : coupons) {
            cp.add(Views.map("place", k.place, "label", k.label, "steps", k.steps, "turn", DoseModel.turnNoun(k.steps),
                    "dots", k.dots, "pitchMm", r2(k.pitch), "expectedMg", Math.round(k.expectedMg)));
        }
        m.put("coupons", cp);
        m.put("notWeighed", notWeighed);
        DispenseConfig.PurgeRow row = c.purgeRow;
        m.put("purgeSpots", Views.map("need", purgeNeed, "left", row.spotsLeft()));
        m.put("home", Views.home(home));
        m.put("homes", homes); // under homeBeforeRun "always" one before every engine step
        m.put("zCheck", Views.map("needed", zCheck.needed, "estimateS", zCheck.needed
                ? DispenseService.PROBE_POINT_ESTIMATE_S : 0));
        List<Map<String, Object>> probes = new ArrayList<>();
        for (double[] p : area == null ? new ArrayList<double[]>() : blankTargets) {
            probes.add(Views.map("surface", area.surfaceName, "x", r2(p[0]), "y", r2(p[1])));
        }
        for (CouponPlan k : coupons) {
            if (k.rect != null) {
                for (double[] p : WeighPlan.touches(k.rect)) {
                    probes.add(Views.map("surface", k.place, "x", r2(p[0]), "y", r2(p[1])));
                }
            }
        }
        m.put("probes", probes);
        m.put("groups", groupsView());
        List<Map<String, Object>> saves = new ArrayList<>();
        List<String> demotes = new ArrayList<>();
        for (GroupPlan g : groups) {
            if (g.group != null) {
                saves.add(Views.map("group", g.group, "kind", g.kind, "typeKey", g.typeKey, "steps", g.steps,
                        "perPad", g.perPad));
            }
            if (g.validated) {
                demotes.add(g.typeKey);
            }
        }
        m.put("saves", saves);
        m.put("demotes", demotes);
        m.put("skipped", skipped);
        m.put("needs", needsWords(c));
        m.put("notes", notes);
        m.put("sentence", sentence(c));
        m.put("needsText", needsText(c));
        m.put("source", entry != null ? Views.map("planId", entry.plan.planId, "boardName", entry.plan.boardName)
                : boardFile != null ? Views.map("boardFile", boardFile, "boardName", boardName) : null);
        m.put("manual", manual);
        m.put("refusal", refusal);
        m.put("refusalCode", refusal == null ? null : refusalCode);
        return m;
    }

    /** The landing check's band and search (§22.6 preview: the band and R), or null for the other jobs. */
    private Map<String, Object> landingPreview() {
        if (!MEASURE_LANDING.equals(measure)) {
            return null;
        }
        PadLadder.LandingLayout l = landingLayout;
        boolean fits = l != null && l.problem == null;
        return Views.map("searchMm", searchMm, "wide", req.wide, "expected", expected == null ? null
                : new double[] {r3(expected[0]), r3(expected[1])}, "bandMm", l == null ? null : r1(l.reservedMm),
                "band", fits ? Views.map("x0", r1(l.x0), "x1", r1(l.x1), "y0", r1(l.y0), "y1", r1(l.y1)) : null,
                "frames", fits ? l.frames.size() : null, "problem", l == null ? null : l.problem);
    }

    private List<String> needsWords(DispenseConfig c) {
        List<String> l = new ArrayList<>();
        if (!coupons.isEmpty()) {
            double[] size = c.padCalibration.couponSizeMm;
            l.add((coupons.size() == 1 ? "a clean weigh coupon on " : numberWord(coupons.size())
                    + " clean weigh coupons on ") + couponWords() + String.format(Locale.ROOT, " (blank PCB, %.0f × "
                            + "%.0f mm, at least 3 g)", size[0], size[1]));
            l.add("the balance");
            l.add("a lint-free wipe and isopropyl alcohol");
        }
        return l;
    }

    private String needsText(DispenseConfig c) {
        List<String> l = needsWords(c);
        return l.isEmpty() ? null : "You need: " + joinWords(l) + ".";
    }

    private static String joinWords(List<String> l) {
        if (l.size() <= 1) {
            return String.join("", l);
        }
        return String.join(", ", l.subList(0, l.size() - 1)) + ", " + l.get(l.size() - 1);
    }

    /** §7.9's sentence: what moves, where, how long, when it needs you. */
    String sentence(DispenseConfig c) {
        List<String> s = new ArrayList<>();
        boolean homing = home != null && homes > 0;
        boolean z = zCheck != null && zCheck.needed;
        if (homing || z) {
            s.add((!homing ? "" : homes > 1 ? "Homes before each of its " + homes + " steps (the setting is: always "
                    + "home first; ≈ " + minSec(homes * home.estimateS) + ")" : "Homes first (≈ "
                            + minSec(home.estimateS) + ")")
                    + (homing && z ? " and checks" : z ? "Checks" : "") + (z ? " Z (≈ "
                            + minSec(DispenseService.PROBE_POINT_ESTIMATE_S) + ")" : "") + ".");
        }
        Step b = find("blank");
        if (b != null && blankNeeded) {
            s.add("Probes the blank with N1 (" + blankTargets.size() + " points, ≈ " + minSec(b.estimateS) + ").");
        }
        if (MEASURE_LANDING.equals(measure)) {
            PadLadder.LandingLayout l = landingLayout;
            if (l == null || l.problem != null || area == null) {
                return String.join(" ", s);
            }
            s.add(String.format(Locale.ROOT, "Lays one dot, then %d purge dots and %d dots in a row on the blank (X "
                    + "%.1f–%.1f, Y %.1f–%.1f), looks at them, and saves where the needle lands. Uses %s mm of the "
                    + "calibration area (%s left). Searches ±%s mm.", PadLadder.LANDING_PURGE_DOTS,
                    LandingFit.ROW_MM.length, l.x0, l.x1, l.y0, l.y1, Texts.num(l.reservedMm, 0),
                    Texts.num(area.leftMm(), 1), Texts.num(searchMm, 0)));
            return String.join(" ", s);
        }
        Step k = find("coupons");
        if (k != null) {
            s.add("Checks and touches " + (coupons.size() == 1 ? "your weigh coupon" : "your " + numberWord(coupons
                    .size()) + " weigh coupons") + " (≈ " + minSec(k.estimateS) + ").");
        }
        Step l = find(LADDER);
        if (l != null && layout != null) {
            int dots = 0;
            List<String> per = new ArrayList<>();
            for (DoseBlocksRequest.Block x : layout.blocks) {
                dots += x.xy.size();
                per.add(x.xy.size() + " " + DoseModel.turnName(x.netSteps));
            }
            double left = area.leftMm() - layout.reservedMm;
            s.add(String.format(Locale.ROOT, "Lays %d test dots on the blank (%s) in X %.1f–%.1f mm, Y %.1f–%.1f mm (%.1f "
                    + "of %.1f mm left after this) and looks at them with the top camera (≈ %s).", dots, String.join(", ",
                            per), layout.x0, layout.x1, layout.y0, layout.y1, Math.max(0, left), area.heightMm(),
                    minSec(l.estimateS)));
        }
        Step w = find(WEIGH);
        if (w != null) {
            List<String> per = new ArrayList<>();
            for (CouponPlan x : coupons) {
                per.add(x.dots + " " + DoseModel.turnName(x.steps) + " dots on " + x.label.toLowerCase(Locale.ROOT));
            }
            s.add("Then lays " + joinAnd(per) + " (≈ " + minSec(w.estimateS) + ") and parks the head over the purge "
                    + "row. Then it needs you once: weigh each coupon, wipe it and weigh it again ("
                    + 2 * coupons.size() + " readings).");
        }
        if (s.isEmpty()) {
            return "Nothing to dispense: the blank and coupon results are still fresh; it decides and plans again.";
        }
        s.add(String.format(Locale.ROOT, "About %s g of paste, ≈ %s%s.", Texts.num(pasteMg / 1000, 2),
                minSec(estimatedSeconds), w != null ? " before your readings" : ""));
        return String.join(" ", s);
    }

    private static String joinAnd(List<String> l) {
        if (l.size() <= 1) {
            return String.join("", l);
        }
        return String.join(", ", l.subList(0, l.size() - 1)) + " and " + l.get(l.size() - 1);
    }

    private static String numberWord(int n) {
        return n == 2 ? "two" : n == 3 ? "three" : Integer.toString(n);
    }

    static String minSec(double s) {
        long t = Math.round(s);
        return t < 60 ? t + " s" : String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60);
    }

    private static Double r3(Double x) {
        return x == null ? null : Math.round(x * 1000) / 1000.0;
    }

    private static double r1(double x) {
        return Math.round(x * 10) / 10.0;
    }

    private static double r2(double x) {
        return Math.round(x * 100) / 100.0;
    }

    private List<Map<String, Object>> groupsView() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (GroupPlan g : groups) {
            l.add(Views.map("group", g.group, "label", g.label, "kind", g.kind, "typeKey", g.typeKey, "steps", g.steps,
                    "perPad", g.perPad, "turn", DoseModel.turnNoun(g.steps), "doses", g.doses, "hopMm", r2(g.hopMm),
                    "validated", g.validated));
        }
        return l;
    }

    /** status.padCalibration (§12.3). */
    Map<String, Object> view() {
        synchronized (lock) {
            return viewLocked();
        }
    }

    private Map<String, Object> viewLocked() {
        DispenseConfig c = svc.getConfig();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("state", state);
        m.put("step", step);
        m.put("manual", manual);
        m.put("measure", measure);
        m.put("forTip", setupKey);
        m.put("tipName", setupName);
        m.put("needleSinceMs", needleSinceMs);
        m.put("startedAtMs", startedAtMs);
        m.put("endedAtMs", endedAtMs == 0 ? null : endedAtMs);
        m.put("planId", entry == null ? null : entry.plan.planId);
        m.put("boardFile", boardFile);
        List<String> gs = new ArrayList<>();
        for (GroupPlan g : groups) {
            gs.add(g.group);
        }
        m.put("groups", gs);
        m.put("typeKeys", typeKeys);
        List<Map<String, Object>> st = new ArrayList<>();
        double left = 0;
        for (Step s : steps) {
            st.add(Views.map("key", s.key, "state", s.state, "text", s.text));
            if ("todo".equals(s.state) || "now".equals(s.state)) {
                left += s.estimateS;
            }
        }
        m.put("steps", st);
        m.put("ladder", ladderView());
        m.put("coupons", couponsView());
        if (AWAITING.equals(state)) {
            List<Map<String, Object>> cs = new ArrayList<>();
            long laid = 0;
            for (int i = 0; i < coupons.size(); i++) {
                CouponPlan k = coupons.get(i);
                if (k.record != null && k.mass == null) {
                    int found = k.record.found == null ? k.record.n : k.record.found;
                    cs.add(Views.map("place", k.place, "label", k.label, "steps", k.steps, "turn", DoseModel.turnNoun(
                            k.steps), "dots", k.record.n, "found", found, "expectedMg", Math.round(found
                                    * countMgPerDot(k.steps)), "recordId", k.record.id, "laidAtMs",
                            k.record.createdAtMs, "crop", couponCrop(i)));
                    laid = Math.max(laid, k.record.createdAtMs);
                }
            }
            long confirmAfter = laid + (long) (c.padCalibration.readingConfirmAfterMin * 60000);
            long staleAt = laid + (long) (c.padCalibration.doseRecordMaxAgeH * 3600e3);
            m.put("awaiting", Views.map("kind", "readings", "text", askText(), "sinceMs", awaitingSinceMs,
                    "confirmAfterMs", confirmAfter, "staleAtMs", staleAt, "coupons", cs, "unit", "g", "resolutionMg",
                    balance == null ? 1.0 : balance.resolutionMg()));
        }
        else {
            m.put("awaiting", null);
        }
        m.put("pendingCoupons", PadCalibrationEndpoints.pendingView(svc));
        m.put("balance", Views.map("kind", balance == null ? "manual" : balance.kind(), "connected",
                balance != null && balance.isConnected()));
        m.put("estimatedSecondsLeft", ended() || AWAITING.equals(state) ? null : Math.round(left));
        m.put("warnings", warnings);
        m.put("result", result);
        return m;
    }

    /** §7.5's prompt. */
    String askText() {
        List<String> per = new ArrayList<>();
        for (CouponPlan k : coupons) {
            if (k.record != null) {
                int found = k.record.found == null ? k.record.n : k.record.found;
                per.add(String.format(Locale.ROOT, "%s has %d %s dots (about %.3f g)", k.label, k.record.n,
                        DoseModel.turnName(k.steps), found * countMgPerDot(k.steps) / 1000));
            }
        }
        return "The dots are laid and the head is parked over the purge row. For each coupon: put it on the balance "
                + "and type the reading; wipe the paste off with a lint-free wipe and isopropyl alcohol, let it dry, put "
                + "it back on the balance and type the second reading. " + joinSemi(per) + ". Waiting for you since "
                + ZCheck.clock(awaitingSinceMs == 0 ? System.currentTimeMillis() : awaitingSinceMs) + ".";
    }

    private static String joinSemi(List<String> l) {
        return String.join("; ", l);
    }

    /** The median crop of a coupon's weigh dots (the weigh step lays one block per coupon, in coupon order). */
    private Map<String, Object> couponCrop(int coupon) {
        Map<String, Object> any = null;
        for (Map.Entry<String, DoseBlocksResult.Crop> e : crops.entrySet()) {
            if (e.getKey().startsWith("w") && e.getValue().block == coupon) {
                any = any != null && !"median".equals(e.getValue().what) ? any : cropView(e.getKey(), e.getValue());
            }
        }
        return any;
    }

    private Map<String, Object> cropView(String cid, DoseBlocksResult.Crop cr) {
        String base = "/api/pad-calibrations/" + id + "/crops/" + cid;
        int s = cropSteps.get(cid);
        return Views.map("id", cid, "steps", s, "turn", DoseModel.turnNoun(s), "dot", cr.dot + 1, "what", cr.what,
                "weigh", cid.startsWith("w"), "before", base + "-before.png", "after", base + "-after.png", "alt",
                DoseModel.turnNoun(s) + " dot " + (cr.dot + 1) + " (" + cr.what + ")");
    }

    /** The PNG of a crop ({@code <id>-before} | {@code <id>-after}), or null. */
    byte[] crop(String name) {
        boolean after = name.endsWith("-after");
        String cid = name.replaceAll("-(before|after)$", "");
        DoseBlocksResult.Crop cr;
        synchronized (lock) {
            cr = crops.get(cid);
        }
        return cr == null ? null : after ? cr.afterPng : cr.beforePng;
    }

    private List<Map<String, Object>> ladderView() {
        List<Map<String, Object>> l = new ArrayList<>();
        Set<Integer> doses = new LinkedHashSet<>();
        for (PadLadder.Dose d : ladderDoses) {
            doses.add(d.steps);
        }
        for (CouponPlan k : coupons) {
            doses.add(k.steps);
        }
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            if (e == null || !e.opticalFresh) {
                l.add(Views.map("steps", s, "turn", DoseModel.turnNoun(s), "dots", 0, "pass", null));
                continue;
            }
            l.add(Views.map("steps", s, "turn", DoseModel.turnNoun(s), "dots", e.n, "found", e.n - e.misses, "hops",
                    e.hops, "threads", e.threads, "residCv", r3(e.residCv), "phase", r3(e.phaseStat), "phaseLimit",
                    e.phaseLimit, "dMeanMm", r3(e.dMeanMm), "d95Mm", r3(e.d95Mm), "driftPct", r3(e.driftPct),
                    "landingMeanMm", r3(e.landingMeanMm), "mgPerDot", r3(e.mgPerDot), "massConfirmed", e.massConfirmed,
                    "pass", e.opticalPass, "failures", e.failures));
        }
        return l;
    }

    private List<Map<String, Object>> couponsView() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (CouponPlan k : coupons) {
            l.add(Views.map("place", k.place, "label", k.label, "steps", k.steps, "turn", DoseModel.turnNoun(k.steps),
                    "dots", k.dots, "recordId", k.record == null ? null : k.record.id, "found", k.record == null ? null
                            : k.record.found, "totalMg", k.mass == null ? null : k.mass.totalMg, "mgPerDot", k.mass == null
                                    ? null : k.mass.mgPerDot, "skipped", k.mass != null && k.mass.totalMg == null));
        }
        return l;
    }

    /** The result card (§7.10): the first sentence, per group the checks, the crops, P1-P8, the control's drift. */
    private Map<String, Object> resultView(String outcome, String why) {
        String clock = ZCheck.clock(System.currentTimeMillis());
        String sentence;
        String kind;
        if (DONE.equals(outcome) && MEASURE_LANDING.equals(measure)) {
            LandingResult l = landing;
            kind = l != null && l.saved ? "measured" : l != null && l.question != null ? "question" : "not-found";
            sentence = landingText != null ? landingText : "Checked " + clock + ": nothing found.";
        }
        else if (DONE.equals(outcome) && MEASURE_TIP.equals(measure)) {
            kind = "measured";
            sentence = tipSentence(clock);
        }
        else if (DONE.equals(outcome) && manual == null) {
            List<String> ok = new ArrayList<>();
            List<String> not = new ArrayList<>();
            for (GroupPlan g : groups) {
                (PadCalibrationRecord.CHARACTERIZED.equals(g.outcome) ? ok : not).add(g.text);
            }
            String planned = newPlanId != null ? "; planned again" : "";
            if (not.isEmpty()) {
                kind = "characterized";
                sentence = "Characterized " + clock + ": " + String.join("; ", ok) + planned + ".";
            }
            else if (ok.isEmpty()) {
                kind = "provisional";
                sentence = "Not characterized " + clock + ": " + String.join("; ", not) + planned + ".";
            }
            else {
                kind = "partly";
                sentence = "Characterized " + clock + ": " + String.join("; ", ok) + "; " + String.join("; ", not)
                        + planned + ".";
            }
        }
        else if (DONE.equals(outcome)) {
            kind = "measured";
            sentence = manualSentence(clock);
        }
        else if (FAILED.equals(outcome)) {
            kind = "failed";
            sentence = "Failed " + clock + ": " + why;
        }
        else if (tipHalt != null && STOPPED.equals(outcome)) {
            // a mount or a needle change (§22.3): its own words, nothing saved
            kind = "stopped";
            sentence = tipHalt;
        }
        else {
            kind = STOPPED.equals(outcome) ? "stopped" : "cancelled";
            String kept = recordIds.isEmpty() ? "nothing was measured" : "the blank results were kept";
            String coupon = "";
            DispenseConfig c = svc.getConfig();
            long staleAt = 0;
            for (CouponPlan k : coupons) {
                if (k.record != null && k.mass == null) {
                    staleAt = Math.max(staleAt, k.record.createdAtMs + (long) (c.padCalibration.doseRecordMaxAgeH
                            * 3600e3));
                }
            }
            if (staleAt > 0) {
                coupon = "; the coupons still need weighing until " + ZCheck.clock(staleAt);
            }
            sentence = (STOPPED.equals(outcome) ? "Stopped " : "Cancelled ") + clock + " during " + (step == null
                    ? "its start" : step) + ": "
                    + kept + coupon + ".";
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", kind);
        m.put("sentence", sentence);
        m.put("atMs", System.currentTimeMillis());
        m.put("newPlanId", newPlanId);
        m.put("planError", planError);
        m.put("refusal", FAILED.equals(outcome) ? why : null);
        m.put("groups", groupsResult());
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Map.Entry<String, DoseBlocksResult.Crop> e : crops.entrySet()) {
            cs.add(cropView(e.getKey(), e.getValue()));
        }
        m.put("crops", cs);
        m.put("predictions", predictions());
        m.put("control", Views.map("pass", controlPass, "text", controlText, "medianAreaMm2", r3(controlMedianMm2),
                "drift", driftSentence()));
        m.put("pendingCoupons", hasPending());
        m.put("finishable", (STOPPED.equals(outcome) || CANCELLED.equals(outcome)) && tipHalt == null);
        m.put("photos", Views.photos(new ArrayList<>(photos)));
        m.put("landing", landingView());
        m.put("tipDots", MEASURE_TIP.equals(measure) ? tipDots() : null);
        m.put("landingWarnings", landingWarnings());
        return m;
    }

    /** The landing check's result (§22.6 result.landing), or null for the other jobs. */
    private Map<String, Object> landingView() {
        LandingResult l = landing;
        if (!MEASURE_LANDING.equals(measure)) {
            return null;
        }
        if (l == null) {
            return Views.map("saved", false, "searchMm", searchMm, "text", landingText);
        }
        // without a locator dot nothing was measured: no landing numbers (a 0, 0 would read as a landing)
        boolean measured = l.coarse != null;
        return Views.map("jobId", l.jobId, "setupKey", l.setupKey, "needleSinceMs", l.needleSinceMs, "dxMm", measured
                ? r3(l.dxMm) : null, "dyMm", measured ? r3(l.dyMm) : null, "sdMm", r3(l.sdMm), "n", l.n, "found",
                l.found, "searchMm", l.searchMm, "expected",
                l.expected, "coarse", l.coarse, "residual", l.residual, "pairs", l.pairs, "photoId", l.photoId,
                "headOffset", l.headOffset, "saved", l.saved, "question", l.question, "text", landingText);
    }

    /**
     * Measure its dots (§22.6 result.tipDots): per dose the dots found, their mean and d95 size, threads in hops, the
     * spread and whether every camera check passed; the mass per turn of the dot table now.
     */
    private Map<String, Object> tipDots() {
        List<Map<String, Object>> doses = new ArrayList<>();
        for (PadLadder.Dose d : dosesUp()) {
            DoseEvidence e = evidence.get(d.steps);
            boolean seen = e != null && e.opticalFresh;
            doses.add(Views.map("steps", d.steps, "turn", DoseModel.turnNoun(d.steps), "dots", seen ? e.n : 0, "found",
                    seen ? e.n - e.misses : null, "dMeanMm", seen ? r3(e.dMeanMm) : null, "d95Mm", seen ? r3(e.d95Mm)
                            : null, "threads", seen ? e.threads : null, "hops", seen ? e.hops : null, "spread", seen
                                    ? r3(e.residCv) : null, "pass", seen ? e.opticalPass : null, "failures", seen
                                            ? e.failures : null));
        }
        Tip now = mountedTip(svc);
        int perRev = svc.getConfig().bStepsPerRev;
        return Views.map("doses", doses, "mgPerTurn", now.mgPerStep == null ? null : r3(now.mgPerStep * perRev),
                "confirmed", now.mgPerStepConfirmed, "measured", now.isMeasured(), "dots", now.dotsSource());
    }

    /** The ladder's doses, smallest first. */
    private List<PadLadder.Dose> dosesUp() {
        List<PadLadder.Dose> l = new ArrayList<>(ladderDoses);
        l.sort((a, b) -> Integer.compare(a.steps, b.steps));
        return l;
    }

    /** "Measured {HH:MM}: the {S}'s dots: quarter 0.71, half 0.93, one turn 1.12 mm; 1.57 mg per turn." */
    private String tipSentence(String clock) {
        List<String> sizes = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (PadLadder.Dose d : dosesUp()) {
            DoseEvidence e = evidence.get(d.steps);
            String turn = d.steps == DoseModel.ONE_TURN ? "one turn" : DoseModel.turnNoun(d.steps).replace(" turn", "");
            if (e == null || !e.opticalFresh || e.dMeanMm == null) {
                failed.add("no " + DoseModel.turnName(d.steps) + " dots were measured");
            }
            else {
                sizes.add(turn + " " + Texts.mm(e.dMeanMm));
                if (!e.opticalPass && !e.failures.isEmpty()) {
                    failed.add(e.failures.get(0));
                }
            }
        }
        Tip now = mountedTip(svc);
        String mass = now.mgPerStep == null ? "not weighed" : Texts.num(now.mgPerStep * svc.getConfig().bStepsPerRev, 2)
                + " mg per turn" + (now.mgPerStepConfirmed ? "" : " (weighed once)");
        return "Measured " + clock + ": the " + setupName + "'s dots: " + (sizes.isEmpty() ? "none"
                : String.join(", ", sizes) + " mm") + "; " + mass + (failed.isEmpty() ? "." : "; " + String.join("; ",
                        failed) + ".");
    }

    /**
     * §22.3: "The dots land {dx}, {dy} mm off their spots: check where the {S} lands." for a ladder dose whose mean
     * landing per axis is more than 0.15 mm off.
     */
    private List<String> landingWarnings() {
        List<String> l = new ArrayList<>();
        try {
            for (Long rid : recordIds) {
                DoseRecord r = svc.getDb().doseRecord(rid);
                if (r == null || !DoseRecord.OPTICAL.equals(r.kind) || r.landingDxMm == null || r.landingDyMm == null) {
                    continue;
                }
                if (Math.hypot(r.landingDxMm, r.landingDyMm) > LandingFit.SCATTER_MAX_MM + Limits.EPS) {
                    l.add(String.format(Locale.ROOT, "The dots land %s, %s mm off their spots: check where the %s lands.",
                            Views.signed(r.landingDxMm), Views.signed(r.landingDyMm), setupName));
                    break;
                }
            }
        }
        catch (Exception e) {
            Logger.warn(e, "pad calibration {}: the landing of its records was not read", id);
        }
        return l;
    }

    private boolean hasPending() {
        if (voidedBy != null) {
            return false; // the needle change wrote their skip rows (§22.3)
        }
        for (CouponPlan k : coupons) {
            if (k.record != null && k.mass == null) {
                return true;
            }
        }
        return false;
    }

    private String manualSentence(String clock) {
        int s = groups.isEmpty() ? 0 : groups.get(0).steps;
        DoseEvidence e = evidence.get(s);
        if (WEIGH.equals(manual)) {
            return "Weighed " + clock + ": " + readingWords() + (e != null && e.massConfirmed
                    ? "; the mass is confirmed." : "; one weighing does not confirm it yet.");
        }
        if (e == null || !e.opticalFresh) {
            return "Measured " + clock + ": no " + DoseModel.turnNoun(s) + " dots were measured.";
        }
        // the fresh camera checks of the dose: the blank's test dots and the coupons' weigh dots (rc1 review)
        return "Measured " + clock + ": " + e.n + " " + DoseModel.turnName(s) + " dots checked by the camera: "
                + (e.opticalPass ? "every camera check passed." : e.failures.get(0) + ".");
    }

    private List<Map<String, Object>> groupsResult() {
        List<Map<String, Object>> l = new ArrayList<>();
        for (GroupPlan g : groups) {
            Decider.Decision d = g.decision;
            DoseEvidence e = d == null ? null : evidence.get(d.steps);
            List<Map<String, Object>> checks = new ArrayList<>();
            if (d != null) {
                for (Decider.Check k : d.checks) {
                    checks.add(Views.map("key", k.key, "ok", k.ok, "predicted", k.predicted, "measured", k.measured,
                            "limit", k.limit, "text", k.text));
                }
            }
            l.add(Views.map("group", g.group, "label", g.label, "typeKey", g.typeKey, "outcome", g.outcome, "text",
                    g.text, "steps", d == null ? g.steps : d.steps, "perPad", d == null ? g.perPad : d.perPad, "turn",
                    DoseModel.turnNoun(d == null ? g.steps : d.steps), "mgPerPad", d == null ? null : d.massPerPadMg,
                    "targetMg", d == null ? g.targetMg : d.targetMg, "errorPct", d == null ? null : d.errorPct,
                    "dMeanMm", e == null ? null : r3(e.dMeanMm), "d95Mm", e == null ? null : r3(e.d95Mm), "residCv",
                    e == null ? null : r3(e.residCv), "phase", e == null ? null : r3(e.phaseStat), "phaseLimit", e == null
                            ? null : e.phaseLimit, "threads", e == null ? null : e.threads, "hops", e == null ? null
                                    : e.hops, "massReadings", e == null ? null : e.massReadings, "massConfirmed",
                    e != null && e.massConfirmed, "padRipplePct", d == null ? null : d.padRipplePct,
                    "measuredRipplePct", d == null ? null : d.measuredRipplePct, "replaced", d != null && d.replaced,
                    "replacedText", d == null ? null : d.replacedText, "notes", d == null ? null : d.notes, "checks",
                    checks, "version", g.record == null ? null : g.record.versionTo, "calibrationId", g.record == null
                            ? null : g.record.id));
            addRecord(l.get(l.size() - 1), g.record);
        }
        return l;
    }

    /**
     * A group's "calibrated for" label and the pictures linked to its record (§22.5, §22.7). §22.6 calls the label
     * {@code label}, but a group's {@code label} is its own name (§7.10, the result card's heading): the record's label
     * is {@code calibrationLabel}, beside {@code calibrationId}.
     */
    private void addRecord(Map<String, Object> m, PadCalibrationRecord rec) {
        Object[] label = null;
        List<Map<String, Object>> pics = new ArrayList<>();
        if (rec != null) {
            try {
                label = PadCalibrationEndpoints.label(svc, rec);
                pics = Views.photos(svc.getDb().photos(null, rec.id, null, null, false, 50));
            }
            catch (Exception e) {
                Logger.warn(e, "pad calibration {}: the label or the photos of record {} were not read", id, rec.id);
            }
        }
        m.put("calibrationLabel", label == null ? null : label[0]);
        m.put("calibrationLabelAuto", label == null ? null : label[1]);
        m.put("photos", pics);
    }

    /** The control's drift against the weighed half turn the user validated (§1.3), or null. */
    String driftSentence() {
        DispenseConfig c = svc.getConfig();
        int s = c.profile.netStepsPerDot;
        DoseEvidence e = evidence.get(s);
        if (tip == null || !tip.benchModel || s != DoseModel.HALF || e == null || e.mgPerDot == null) {
            return null; // the bench's weighed half turn is the 22G bent taper's (§22.2)
        }
        double drift = e.mgPerDot / Anchor.BENCH.massPerPadMg - 1;
        return String.format(Locale.ROOT, "the validated half turn now gives %s mg, %s %% %s than the %s mg weighed on "
                + "2026-10-27", Texts.mg(e.mgPerDot), Texts.pct(drift), drift < 0 ? "less" : "more",
                Texts.mg(Anchor.BENCH.massPerPadMg));
    }

    /** P1-P8 of §1.8 with this job's values. */
    private List<Map<String, Object>> predictions() {
        List<Map<String, Object>> l = new ArrayList<>();
        Set<Integer> doses = new java.util.TreeSet<>();
        for (PadLadder.Dose d : ladderDoses) {
            doses.add(d.steps);
        }
        for (CouponPlan k : coupons) {
            doses.add(k.steps);
        }
        List<String> perStep = new ArrayList<>();
        Boolean p1 = null;
        double min = Double.MAX_VALUE, max = 0;
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            if (e != null && e.mgPerStep != null) {
                double ug = e.mgPerStep * 1000;
                perStep.add(DoseModel.turnNoun(s) + " " + Texts.num(ug, 2) + " µg/step");
                min = Math.min(min, ug);
                max = Math.max(max, ug);
                // the 1.32 µg/step is the 22G bent taper's at 18 psi: another tip is compared across its doses only
                p1 = (p1 == null || p1) && (!bench() || Math.abs(ug / 1.32 - 1) <= 0.10 + Limits.EPS);
            }
        }
        if (perStep.size() >= 2 && p1 != null) {
            p1 &= max / min - 1 <= 0.05 + Limits.EPS;
        }
        l.add(pred("P1", "mass per step equal across doses, 1.32 µg/step at 18 psi",
                "within ±5 % of each other, each ±10 % of 1.32", perStep.isEmpty() ? null : String.join(", ", perStep),
                p1));
        Boolean p2 = null;
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            if (e != null && e.massReadings >= 1) {
                p2 = (p2 == null || p2) && e.massConfirmed;
            }
        }
        l.add(pred("P2", "two readings of one dose agree", "±5 %", p2 == null ? null : p2 ? "confirmed"
                : "not confirmed", p2));
        List<String> sizes = new ArrayList<>();
        Boolean p3 = null;
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            Tip.Measured own = bench() || tip == null || tip.measured == null ? null : tip.measured.get(s);
            if (e != null && e.dMeanMm != null && (bench() || own != null)) {
                // the bench model's band with the 22G; another tip's own dot table (as the job started) ±10 %
                double lo = !bench() ? own.dMeanMm * 0.90 : s == DoseModel.QUARTER ? DoseModel.dLoMm(s)
                        : DoseModel.dHiMm(s) * 0.92;
                double hi = !bench() ? own.dMeanMm * 1.10 : s == DoseModel.QUARTER ? DoseModel.dHiMm(s)
                        : DoseModel.dHiMm(s) * 1.08;
                boolean in = e.dMeanMm >= lo - Limits.EPS && e.dMeanMm <= hi + Limits.EPS;
                sizes.add(DoseModel.turnNoun(s) + " d " + Texts.mm(e.dMeanMm) + " mm (" + Texts.mm(lo) + "–"
                        + Texts.mm(hi) + ")");
                p3 = (p3 == null || p3) && in;
            }
        }
        l.add(pred("P3", "blank dot inside the model's band", "as stated per dose", sizes.isEmpty() ? null
                : String.join(", ", sizes), p3));
        l.add(statPred("P4", "phase: half split ≤ 13 %, quarter amplitude ≤ 0.20", "upper 95 % bound inside", doses,
                e -> e.phaseStat, e -> e.phaseLimit));
        l.add(statPred("P5", "residual area CV ≤ 13 %", "upper 95 % bound inside", doses, e -> e.residCv,
                e -> svc.getConfig().padCalibration.maxResidCv));
        List<String> th = new ArrayList<>();
        Boolean p6 = null;
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            if (e != null && e.opticalFresh && e.hops > 0) {
                int k95 = LadderStats.k95(e.hops, svc.getConfig().padCalibration.threadRateOk);
                th.add(DoseModel.turnNoun(s) + " " + e.threads + " in " + e.hops + " (at most " + k95 + ")");
                p6 = (p6 == null || p6) && e.threads <= k95;
            }
        }
        l.add(pred("P6", "threads between consecutive dots ≤ 3 %", "≤ k95(hops, 3 %) in ≥ 60 hops", th.isEmpty() ? null
                : String.join(", ", th), p6));
        int[] p7 = afterSwitch();
        l.add(pred("P7", "after a block switch the first 2 dots are within 2σ of their block", "reported, ≥ 95 %",
                p7[1] == 0 ? null : p7[0] + " of " + p7[1] + " within 2σ", p7[1] == 0 ? null
                        : p7[0] >= 0.95 * p7[1] - Limits.EPS));
        Boolean p8 = controlMedianMm2 == null || !bench() ? null
                : Math.abs(controlMedianMm2 / Limits.BENCH_CONTROL_AREA_MM2 - 1) <= 0.15 + Limits.EPS;
        l.add(pred("P8", bench() ? "the control matches the bench: 1.057 mm² ± 15 %" : "the control matches the bench "
                + "(the 22G bent taper's only)", "as stated", controlMedianMm2 == null ? null
                        : Texts.num(controlMedianMm2, 3) + " mm²", p8));
        return l;
    }

    /** The tip is the bench's 22G (§1's model describes it). */
    private boolean bench() {
        return tip == null || tip.benchModel;
    }

    /** P7: the first 2 found dots of every block after a switch, within 2σ of the rest of their block: {in, of}. */
    private int[] afterSwitch() {
        int in = 0;
        int of = 0;
        for (DoseBlocksResult res : results) {
            for (int k = 1; k < res.blocks.size(); k++) {
                List<DoseBlocksResult.DotOut> dots = res.blocks.get(k).dots;
                List<Double> rest = new ArrayList<>();
                for (int i = Limits.EXCLUDED_AFTER_CROSS; i < dots.size(); i++) {
                    if (dots.get(i).found != null) {
                        rest.add(dots.get(i).found.areaMm2);
                    }
                }
                if (rest.size() < 5) {
                    continue;
                }
                double mean = rest.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double ss = 0;
                for (double a : rest) {
                    ss += (a - mean) * (a - mean);
                }
                double sd = Math.sqrt(ss / (rest.size() - 1));
                for (int i = 0; i < Math.min(Limits.EXCLUDED_AFTER_CROSS, dots.size()); i++) {
                    of++;
                    DoseBlocksResult.DotOut d = dots.get(i);
                    in += d.found != null && Math.abs(d.found.areaMm2 - mean) <= 2 * sd ? 1 : 0;
                }
            }
        }
        return new int[] {in, of};
    }

    private Map<String, Object> statPred(String id, String what, String band, Set<Integer> doses,
            java.util.function.Function<DoseEvidence, Double> value,
            java.util.function.Function<DoseEvidence, Double> limit) {
        List<String> vs = new ArrayList<>();
        Boolean ok = null;
        for (int s : doses) {
            DoseEvidence e = evidence.get(s);
            if (e != null && value.apply(e) != null && limit.apply(e) != null) {
                vs.add(DoseModel.turnNoun(s) + " " + Texts.num(value.apply(e), 3) + " (≤ " + Texts.num(limit.apply(e), 2)
                        + ")");
                ok = (ok == null || ok) && value.apply(e) <= limit.apply(e) + Limits.EPS;
            }
        }
        return pred(id, what, band, vs.isEmpty() ? null : String.join(", ", vs), ok);
    }

    private static Map<String, Object> pred(String id, String prediction, String band, String measured, Boolean pass) {
        return Views.map("id", id, "prediction", prediction, "band", band, "measured", measured, "pass", pass);
    }
}
