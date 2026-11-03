package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseProfile;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DoseRecords;
import org.openpnp.instancelabs.dispense.DotLadderRow;
import org.openpnp.instancelabs.dispense.HomeReport;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.MountResult;
import org.openpnp.instancelabs.dispense.NeedleChange;
import org.openpnp.instancelabs.dispense.PadCalibrationRecord;
import org.openpnp.instancelabs.dispense.PadCleanReport;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeStatus;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.PhotoStore;
import org.openpnp.instancelabs.dispense.PlaneReport;
import org.openpnp.instancelabs.dispense.RegistrationResult;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.dispense.TipPictureResult;
import org.openpnp.instancelabs.dispense.TipSetup;
import org.openpnp.instancelabs.dispense.TipSetups;
import org.openpnp.instancelabs.dispense.VacuumProber;
import org.openpnp.instancelabs.padprofile.DoseModel;
import org.openpnp.instancelabs.padprofile.Texts;
import org.openpnp.instancelabs.padprofile.Tip;

/** JSON shapes of engine objects (stable names for the web UI; engine enums as their db names). */
final class Views {
    private Views() {
    }

    static Map<String, Object> interlocks(InterlockReport r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok());
        List<Map<String, Object>> v = new ArrayList<>();
        for (InterlockReport.Violation x : r.violations) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("code", x.code);
            e.put("message", x.message);
            v.add(e);
        }
        m.put("violations", v);
        m.put("notes", new ArrayList<>(r.notes));
        return m;
    }

    /** "298 / 118" for a list of start angles. */
    static String angles(List<Double> a) {
        List<String> l = new ArrayList<>();
        for (Double x : a) {
            l.add(String.format(java.util.Locale.ROOT, "%.0f", x));
        }
        return String.join(" / ", l);
    }

    /**
     * The dispense profile with the derived numbers the UI shows (forward / net turn, steps, feed
     * in deg/min, start angles) and whether the start angle is valid for the current controller
     * connection (I11).
     */
    static Map<String, Object> profile(DispenseConfig c, Long controllerConnectedAtMs) {
        DispenseProfile p = c.profile;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.name);
        m.put("pressurePsi", p.pressurePsi);
        m.put("netStepsPerDot", p.netStepsPerDot);
        m.put("suckBackDeg", p.suckBackDeg);
        m.put("startAngleDeg", p.startAngleDeg);
        m.put("startAngleSetAtMs", p.startAngleSetAtMs);
        m.put("screwRpm", p.screwRpm);
        m.put("screwAccelDegS2", p.screwAccelDegS2);
        m.put("dwellMs", p.dwellMs);
        m.put("gapMm", p.gapMm);
        m.put("purgeDots", p.purgeDots);
        m.put("notes", p.notes);
        m.put("stepsPerRev", c.bStepsPerRev);
        m.put("stepsPerDeg", c.bStepsPerDeg);
        m.put("revolutionsPerDot", p.revolutionsPerDot(c));
        m.put("netDeg", p.netDeg(c));
        m.put("forwardDeg", p.forwardDeg(c));
        m.put("suckBackSteps", p.suckBackSteps(c));
        m.put("feedDegPerMin", p.feedDegS() * 60);
        m.put("partialTurn", p.isPartialTurn(c));
        m.put("startAngles", p.isPartialTurn(c) ? p.startAngles(c) : null);
        boolean stale = p.isPartialTurn(c) && (p.startAngleSetAtMs == null
                || (controllerConnectedAtMs != null && p.startAngleSetAtMs < controllerConnectedAtMs));
        m.put("startAngleNeedsConfirm", stale);
        m.put("controllerConnectedAtMs", controllerConnectedAtMs);
        m.put("fingerprint", p.fingerprint());
        List<String> problems = p.validate(c);
        m.put("problems", problems);
        return m;
    }

    /** The purge row with its spots and how many are left. */
    static Map<String, Object> purgeRow(DispenseConfig c) {
        DispenseConfig.PurgeRow r = c.purgeRow;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("surfaceName", r.surfaceName);
        m.put("x0Mm", r.x0Mm);
        m.put("y0Mm", r.y0Mm);
        m.put("pitchXMm", r.pitchXMm);
        m.put("pitchYMm", r.pitchYMm);
        m.put("spots", r.spots);
        m.put("usedSpots", r.usedSpots);
        m.put("spotsLeft", r.spotsLeft());
        m.put("defined", r.isDefined());
        m.put("resetAtMs", r.resetAtMs);
        m.put("perBoard", c.profile.purgeDots);
        m.put("boardsLeft", c.profile.purgeDots <= 0 ? null : r.spotsLeft() / c.profile.purgeDots);
        m.put("surfaceProbed", c.surface(r.surfaceName) != null);
        return m;
    }

    /** A clean-pad check (I10). */
    static Map<String, Object> cleanCheck(PadCleanReport r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", r.planId);
        m.put("ok", r.ok);
        m.put("grayLevel", r.grayLevel);
        m.put("minBrightFraction", r.minBrightFraction);
        m.put("boxWMm", r.boxWMm);
        m.put("boxHMm", r.boxHMm);
        m.put("checkedAtMs", r.checkedAtMs);
        m.put("complete", r.isComplete());
        m.put("targets", r.pads.size());
        m.put("dirtyPadIds", r.dirtyPadIds());
        List<Map<String, Object>> pads = new ArrayList<>();
        for (PadCleanReport.Pad x : r.pads) {
            pads.add(map("padId", x.padId, "dotIndex", x.dotIndex, "x", x.xMm, "y", x.yMm, "brightFraction",
                    x.brightFraction, "clean", x.clean, "error", x.error, "boxWMm", x.boxWMm, "boxHMm", x.boxHMm));
        }
        m.put("pads", pads);
        return m;
    }

    /** A stored surface map with its plane fit (or the reason it cannot be fitted). */
    static Map<String, Object> surface(String name, org.openpnp.instancelabs.dispense.DispenseConfig.SurfaceMap m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", name);
        o.put("points", m.points);
        o.put("setAtMs", m.setAtMs);
        o.put("note", m.note);
        o.put("boundToBoard", m.boardFingerprint != null);
        o.put("simulated", Boolean.TRUE.equals(m.simulated));
        try {
            org.openpnp.instancelabs.dispense.SurfacePlane pl = org.openpnp.instancelabs.dispense.SurfacePlane
                    .fit(m.points);
            o.put("plane", map("a", pl.a, "b", pl.b, "c", pl.c, "slopeMmPerMm", pl.slope(), "maxResidualMm",
                    pl.maxAbsResidualMm, "minZ", pl.minZ, "maxZ", pl.maxZ, "residuals", pl.residuals));
        }
        catch (IllegalArgumentException e) {
            o.put("plane", null);
            o.put("error", e.getMessage());
        }
        return o;
    }

    /** A finished (or never started) run. Do not call with a run that is still streaming. */
    static Map<String, Object> run(RunResult r, boolean withLines) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.runId);
        m.put("kind", r.kind);
        m.put("status", String.valueOf(r.status));
        m.put("message", r.message);
        m.put("planId", r.planId);
        m.put("boardName", r.boardName);
        m.put("calibrationId", r.calibrationId);
        m.put("startedAtMs", r.startedAtMs);
        m.put("endedAtMs", r.endedAtMs);
        m.put("dotsPlanned", r.dotsPlanned);
        m.put("dotsDone", r.dotsDone);
        m.put("firstDotStartMs", r.firstDotStartMs);
        m.put("lastDotEndMs", r.lastDotEndMs);
        m.put("motionSeconds", r.motionSeconds());
        m.put("totalSeconds", r.totalSeconds());
        m.put("dphMotion", r.dphMotion());
        m.put("dphOverall", r.dphOverall());
        m.put("keepaliveActive", r.keepaliveActive);
        m.put("keepalivePings", r.keepalivePings);
        m.put("linesSent", r.linesSent);
        m.put("warnings", new ArrayList<>(r.warnings));
        m.put("interlocks", interlocks(r.interlocks));
        m.put("profile", r.profile);
        m.put("cleanCheck", cleanCheck(r.cleanCheck));
        m.put("purgeDots", r.purgeDots);
        m.put("bCountAtStart", r.bCountAtStart);
        m.put("workStartAngleDeg", r.workStartAngleDeg);
        m.put("nextPlanId", r.nextPlanId);
        m.put("homedFirst", r.homedFirst);
        m.put("zCheck", r.zCheck);
        if (withLines) {
            List<Map<String, Object>> dots = new ArrayList<>();
            for (RunResult.DotTiming t : r.dotTimings) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("dotIndex", t.dotIndex);
                d.put("padId", t.padId);
                d.put("rotationDeg", t.rotationDeg);
                d.put("startMs", t.startMs);
                d.put("endMs", t.endMs);
                dots.add(d);
            }
            m.put("dotTimings", dots);
            m.put("streamedLines", new ArrayList<>(r.streamedLines));
        }
        return m;
    }

    static Map<String, Object> plan(PlanStore.Entry e, InterlockReport preflight) {
        return plan(e, preflight, null);
    }

    /** As above; {@code newSeatAtMs} is the engine's new-seat flag (the registration's beforeNextBoard). */
    static Map<String, Object> plan(PlanStore.Entry e, InterlockReport preflight, Long newSeatAtMs) {
        DispensePlan p = e.plan;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("planId", p.planId);
        m.put("boardName", p.boardName);
        m.put("boardLocationId", p.boardLocationId);
        m.put("createdAtMs", p.createdAtMs);
        m.put("source", e.source);
        m.put("profile", p.profile);
        m.put("profileFingerprint", p.profileFingerprint);
        m.put("needleGapMm", p.needleGapMm);
        m.put("needleZOffsetMm", p.needleZOffsetMm);
        m.put("hopHeightMm", p.hopHeightMm);
        m.put("surfaceProbed", p.surfaceFingerprint != null);
        // the tip setup the plan was made for (§22.3: another mounted since, or a new version of it, makes it stale)
        m.put("setupKey", p.setupKey);
        m.put("setupName", p.setupName);
        m.put("setupVersion", p.setupVersion);

        Map<String, Object> s = new LinkedHashMap<>();
        int blocked = p.blockedPads().size();
        s.put("pads", p.pads.size());
        s.put("blockedPads", blocked);
        s.put("dots", p.dots.size());
        s.put("totalRotationDeg", p.totalRotationDeg());
        s.put("pathLengthMm", p.pathLengthMm);
        s.put("pathLengthNearestNeighbourMm", Double.isNaN(p.pathLengthNearestNeighbourMm) ? null
                : p.pathLengthNearestNeighbourMm);
        s.put("estimatedSeconds", e.estimatedSeconds);
        s.put("estimatedPressurisedSeconds", e.estimatedPressurisedSeconds);
        s.put("estimateError", e.estimateError);
        // Whole program (purge, cross, dots, settle, vent): moves with acceleration, dwells; no host
        // latency or look-ahead. The bench's DPH counts from the cross to the last dot, so this is lower.
        s.put("estimatedDph", e.estimatedSeconds == null || e.estimatedSeconds <= 0 || p.dots.isEmpty()
                ? null : p.dots.size() / e.estimatedSeconds * 3600.0);
        s.put("purgeDots", p.profile == null ? 0 : p.profile.purgeDots);
        s.put("estimatedWorkSeconds", e.estimatedWorkSeconds);
        s.put("estimatedWorkDph", e.estimatedWorkSeconds == null || e.estimatedWorkSeconds <= 0 || p.dots.isEmpty()
                ? null : p.dots.size() / e.estimatedWorkSeconds * 3600.0);
        s.put("text", p.summary());
        m.put("summary", s);

        List<Map<String, Object>> pads = new ArrayList<>();
        for (DispensePlan.PlannedPad x : p.pads) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("padId", x.padId);
            d.put("placementId", x.placementId);
            d.put("packageId", x.packageId);
            d.put("typeKey", x.typeKey);
            d.put("typeVersion", x.typeVersion);
            d.put("typePadVersion", x.typePadVersion);
            d.put("typeTipKey", x.typeTipKey);
            d.put("typeStatus", x.typeStatus == null ? null : x.typeStatus.dbName);
            d.put("source", x.source == null ? null : x.source.name());
            d.put("x", x.xMm);
            d.put("y", x.yMm);
            d.put("rotation", x.rotationDeg);
            d.put("w", x.wMm);
            d.put("h", x.hMm);
            d.put("surfaceZ", x.surfaceZMm);
            d.put("areaMm2", x.areaMm2);
            d.put("tMm", x.tMm);
            d.put("k", x.k);
            d.put("targetVolumeMm3", x.targetVolumeMm3);
            d.put("targetMassMg", x.targetMassMg);
            d.put("dotCount", x.dotCount);
            d.put("rotationPerDotDeg", x.rotationPerDotDeg);
            d.put("flags", x.flagNames());
            d.put("blocked", x.isBlocked());
            pads.add(d);
        }
        m.put("pads", pads);

        List<Map<String, Object>> dots = new ArrayList<>();
        for (DispensePlan.PlannedDot x : p.dots) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("index", x.index);
            d.put("padId", x.padId);
            d.put("x", x.xMm);
            d.put("y", x.yMm);
            d.put("surfaceZ", x.surfaceZMm);
            d.put("dispenseZ", x.dispenseZMm);
            d.put("hopZ", x.hopZMm);
            d.put("rotationDeg", x.rotationDeg);
            dots.add(d);
        }
        m.put("dots", dots);
        m.put("warnings", new ArrayList<>(p.warnings));
        m.put("interlocks", interlocks(preflight));
        m.put("cleanCheck", cleanCheck(e.cleanCheck()));
        m.put("cleanCheckRunning", e.cleanCheckRunning());
        m.put("registration", registrationSummary(e.registrationRecord, newSeatAtMs));
        m.put("registrationTargets", e.registrationTargets);
        m.put("registrationRun", registration(e.registration()));
        m.put("registrationRunning", e.registrationRunning());
        BoardPlanView.addTo(m, e);
        PadProfileViews.addTo(m, e, DispenseService.get());
        return m;
    }

    /** The registration a plan was made with (§7.1), or null. */
    static Map<String, Object> registrationSummary(BoardRegistration r, Long newSeatAtMs) {
        if (r == null) {
            return null;
        }
        List<Map<String, Object>> fids = new ArrayList<>();
        for (BoardRegistration.Fid f : r.fiducials) {
            fids.add(map("name", f.name, "x", f.x, "y", f.y));
        }
        return map("id", r.id, "atMs", r.atMs, "method", r.method, "fiducials", fids, "rotationDeg", r.rotationDeg,
                "scaleX", r.scaleX, "scaleY", r.scaleY, "shearDeg", r.shearDeg, "simulated", r.simulated,
                "lastCheckedAtMs", r.lastCheckedAtMs, "lastCheckMovedMm", r.lastCheckMovedMm, "beforeNextBoard",
                newSeatAtMs != null && r.atMs < newSeatAtMs);
    }

    /** Whether a task homes first, and why (machine.home, the 202 bodies). */
    static Map<String, Object> home(HomeTracker.Plan p) {
        if (p == null) {
            return null;
        }
        return map("needed", p.needed, "whyCode", p.whyCode, "why", p.why, "sinceMs", p.sinceMs, "policy", p.policy,
                "homedAtMs", p.homedAtMs, "estimateS", p.estimateS);
    }

    static Map<String, Object> homeReport(HomeReport h) {
        if (h == null) {
            return null;
        }
        return map("needed", h.needed, "whyCode", h.whyCode, "why", h.why, "done", h.done, "seconds", h.seconds,
                "n1RaisedFromZ", h.n1RaisedFromZ);
    }

    static Map<String, Object> window(VacuumProber.Window w) {
        if (w == null) {
            return null;
        }
        return map("nominalZ", w.nominalZ, "refZ", w.refZ, "startZ", w.startZ, "floorZ", w.floorZ);
    }

    static Map<String, Object> plane(PlaneReport p) {
        if (p == null) {
            return null;
        }
        return map("a", p.a, "b", p.b, "c", p.c, "maxResidualMm", p.maxResidualMm, "slopeMmPerMm", p.slopeMmPerMm,
                "minZ", p.minZ, "maxZ", p.maxZ, "coversSpots", p.coversSpots, "spots", p.spots);
    }

    /** A probed point as the result card and the board view show it. */
    static Map<String, Object> probePoint(VacuumProber.Point p) {
        Map<String, Object> m = map("n", p.n, "board", p.bx == null ? null : map("x", p.bx, "y", p.by), "x", p.x, "y",
                p.y, "z", p.z, "state", p.state, "alternate", p.alternate, "baseline", p.baseline, "drop", p.drop,
                "fine", p.fine, "lastOpenZ", p.lastOpenZ, "retried", p.retried, "notes", new ArrayList<>(p.notes));
        m.put("look", p.look == null ? null : map("bare", p.look.bare, "foreignShare", p.look.foreignShare,
                "ringSpread", p.look.ringSpread, "reason", p.look.reason));
        m.put("failure", p.failure);
        return m;
    }

    static List<Map<String, Object>> probePoints(List<VacuumProber.Point> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (l != null) {
            for (VacuumProber.Point p : l) {
                out.add(probePoint(p));
            }
        }
        return out;
    }

    /** The registration view (§7.2) of a finished Register board, or null. */
    static Map<String, Object> registration(RegistrationResult r) {
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok);
        m.put("status", r.run == null ? null : String.valueOf(r.run.status));
        m.put("message", r.message);
        m.put("runId", r.run == null ? null : r.run.runId);
        m.put("atMs", r.run == null ? null : r.run.endedAtMs);
        m.put("simulated", r.simulated);
        m.put("stoppedStage", r.stoppedStage);
        m.put("home", homeReport(r.home));
        List<Map<String, Object>> fids = new ArrayList<>();
        for (RegistrationResult.FidResult f : r.fiducials) {
            fids.add(map("name", f.name, "board", map("x", f.bx, "y", f.by), "expected", map("x", f.expectedX, "y",
                    f.expectedY), "found", f.x == null ? null : map("x", f.x, "y", f.y), "state", f.state, "iterations",
                    f.iterations, "frames", f.frames, "offsetPx", f.offsetPx, "offsetFromExpectedMm",
                    f.offsetFromExpectedMm, "movedMm", f.movedMm, "searchRadiusMm", f.searchRadiusMm));
        }
        m.put("fiducials", fids);
        m.put("fit", r.fit);
        m.put("seat", r.seat);
        m.put("movedMm", r.movedMm);
        m.put("previousAtMs", r.previousAtMs);
        m.put("registrationId", r.registrationId);
        m.put("registrationStored", r.registrationStored);
        m.put("sameSeat", r.sameSeat);
        Map<String, Object> p = null;
        if (r.probe != null) {
            p = map("mode", r.probe.mode, "needed", r.probe.needed, "why", r.probe.why, "skipped", r.probe.skipped,
                    "window", window(r.probe.window), "points", probePoints(r.probe.points), "plane",
                    plane(r.probe.plane), "saved", r.probe.saved, "surfaceSetAtMs", r.probe.surfaceSetAtMs);
        }
        m.put("probe", p);
        m.put("newPlanId", r.newPlanId);
        m.put("probeOnly", r.probeOnly);
        m.put("warnings", r.run == null ? new ArrayList<>() : new ArrayList<>(r.run.warnings));
        return m;
    }

    /** A finished surface probe (§7.4). */
    static Map<String, Object> surfaceProbe(SurfaceProbeResult r) {
        if (r == null) {
            return null;
        }
        return map("ok", r.ok, "status", r.run == null ? null : String.valueOf(r.run.status), "message", r.message,
                "runId", r.run == null ? null : r.run.runId, "stoppedStage", r.stoppedStage, "home", homeReport(r.home),
                "surfaceName", r.surfaceName, "window", window(r.window), "points", probePoints(r.points), "plane",
                plane(r.plane), "saved", r.saved, "warnings", r.run == null ? new ArrayList<>()
                        : new ArrayList<>(r.run.warnings));
    }

    static Map<String, Object> padType(PadType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typeKey", t.typeKey);
        m.put("version", t.version);
        m.put("family", t.family);
        m.put("name", t.name);
        m.put("padWMm", t.padWMm);
        m.put("padHMm", t.padHMm);
        m.put("padRoundness", t.padRoundness);
        m.put("layout", t.layout == null ? null : t.layout.dbName);
        m.put("pitchMm", t.pitchMm);
        m.put("tMm", t.tMm);
        m.put("k", t.k);
        m.put("status", t.status == null ? null : t.status.dbName);
        m.put("packagePattern", t.packagePattern);
        m.put("notes", t.notes);
        m.put("createdAtMs", t.createdAtMs);
        m.put("operator", t.operator);
        PadProfileViews.padTypeFields(m, t);
        // tip setups (§22.2): the row of a setup's composed view
        m.put("setupKey", t.setupKey);
        m.put("padVersion", t.padVersion);
        m.put("standIn", t.standIn);
        return m;
    }

    /** A pad calibration record (§12.2, §22.6): its fields; the list adds its setup, label, photos and flags. */
    static Map<String, Object> padCalibration(PadCalibrationRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("createdAtMs", r.createdAtMs);
        m.put("jobId", r.jobId);
        m.put("typeKey", r.typeKey);
        m.put("versionFrom", r.versionFrom);
        m.put("versionTo", r.versionTo);
        m.put("outcome", r.outcome);
        m.put("steps", r.steps);
        m.put("dotsPerPad", r.dotsPerPad);
        m.put("massPerPadMg", r.massPerPadMg);
        m.put("checks", r.checks);
        m.put("evidence", r.evidence);
        m.put("runs", r.runs);
        m.put("simulated", r.simulated);
        m.put("message", r.message);
        m.put("operator", r.operator);
        m.put("setupKey", r.setupKey);
        m.put("method", r.method);
        m.put("needleSinceMs", r.needleSinceMs);
        return m;
    }

    // ------------------------------------------------------------------------------------------
    // Tip setups and photos (PROFILE_CALIBRATION_DESIGN.md §22.6)
    // ------------------------------------------------------------------------------------------

    /** The URL of a stored picture, or of its 320 px thumbnail. */
    static String photoUrl(long id, boolean thumb) {
        return "/api/photos/" + id + (thumb ? "/thumb.jpg" : ".jpg");
    }

    /** The photo view of §22.6 (removable: a tip or setup picture that is not removed; result photos stay). */
    static Map<String, Object> photo(Photo p) {
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("kind", p.kind);
        m.put("source", p.source);
        m.put("atMs", p.createdAtMs);
        m.put("url", photoUrl(p.id, false));
        m.put("thumbUrl", photoUrl(p.id, true));
        m.put("caption", p.caption);
        m.put("widthPx", p.widthPx);
        m.put("heightPx", p.heightPx);
        m.put("links", p.links == null ? new ArrayList<>() : new ArrayList<>(p.links));
        m.put("removable", !Photo.KIND_RESULT.equals(p.kind) && p.removedAtMs == null);
        m.put("setupKey", p.setupKey);
        m.put("needleSinceMs", p.needleSinceMs);
        m.put("jobId", p.jobId);
        m.put("doseSteps", p.doseSteps);
        m.put("couponPlace", p.couponPlace);
        m.put("mmPerPx", p.mmPerPx);
        m.put("xMm", p.xMm);
        m.put("yMm", p.yMm);
        m.put("bytes", p.bytes);
        m.put("simulated", p.simulated);
        m.put("operator", p.operator);
        m.put("removedAtMs", p.removedAtMs);
        m.put("removedWhy", p.removedWhy);
        m.put("restoreUntilMs", p.removedAtMs == null ? null : p.removedAtMs + PhotoStore.KEEP_REMOVED_MS);
        return m;
    }

    static List<Map<String, Object>> photos(List<Photo> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (l != null) {
            for (Photo p : l) {
                out.add(photo(p));
            }
        }
        return out;
    }

    /** The tip picture task's result (§22.4). */
    static Map<String, Object> tipPicture(TipPictureResult r) {
        if (r == null) {
            return null;
        }
        return map("ok", r.ok, "message", r.message, "run", run(r.run, false), "photo", photo(r.photo));
    }

    /**
     * The setup view of §22.6: the fields, whether it is complete and mounted, what may be edited, its summary, its
     * recipe (parked or live), where it lands, its pad types, its dot sizes and its pictures.
     *
     * @param mountedAtMs when the mounted setup was mounted (null: before the update), for the mounted one
     */
    static Map<String, Object> setup(DispenseService svc, TipSetup s, DispenseConfig cfg, Long mountedAtMs)
            throws Exception {
        DispenseDb db = svc.getDb();
        boolean mounted = s.key.equals(cfg.tip.key);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.key);
        m.put("version", s.version);
        m.put("name", s.name);
        m.put("dispenser", s.dispenser);
        m.put("tipModel", s.tipModel);
        m.put("gauge", s.gauge);
        m.put("idMm", s.idMm);
        m.put("odMm", s.odMm);
        m.put("odSource", s.odSource);
        m.put("form", s.form);
        m.put("bendDeg", s.bendDeg);
        m.put("bendToward", s.bendToward);
        m.put("taper", s.taper);
        m.put("lengthMm", s.lengthMm);
        m.put("minDotSteps", s.minDotSteps);
        m.put("model", s.model);
        m.put("notes", s.notes);
        m.put("createdAtMs", s.createdAtMs);
        m.put("complete", s.isComplete());
        m.put("retired", s.retired);
        m.put("mounted", mounted);
        m.put("mountedAtMs", mounted ? mountedAtMs : null);
        m.put("editable", map("identity", !db.tipSetupInUse(s.key), "correction", true));
        m.put("summary", s.summary());
        DispenseConfig.ParkedTip parked = cfg.parkedTips.get(s.key);
        DispenseProfile p = mounted ? cfg.profile : parked == null ? null : parked.profile;
        m.put("profile", p == null ? null : map("name", p.name, "pressurePsi", p.pressurePsi, "netStepsPerDot",
                p.netStepsPerDot, "parked", !mounted, "from", parked == null ? null : parked.profileFrom));
        DispenseConfig.NeedleLanding l = mounted ? cfg.needleLanding : parked == null ? null : parked.needleLanding;
        m.put("landing", l == null ? null : map("dxMm", l.dxMm, "dyMm", l.dyMm, "setAtMs", l.setAtMs, "checked",
                mounted && svc.isNeedleXyChecked(), "note", l.note, "jobId", l.jobId, "photoId", l.photoId));
        m.put("padTypes", padTypeCounts(TipSetups.typesFor(db, s.key)));
        if (mounted) {
            m.put("dots", dots(svc.mountedTip(), cfg.bStepsPerRev));
        }
        else if (TipSetup.MODEL_BENCH.equals(s.model)) {
            m.put("dots", benchDots(s.minDotSteps));
        }
        else {
            String fp = p == null ? null : p.doseFingerprint();
            boolean sim = svc.isSimulatorConfirmed();
            m.put("dots", dots(DoseRecords.dotTable(db.doseRecordsOf(s.key, fp, cfg.paste.key, sim), s.key, fp,
                    cfg.paste.key, sim, db.flaggedJobs(), DoseRecords.settings(cfg.padCalibration)), s.minDotSteps,
                    cfg.bStepsPerRev));
        }
        m.put("mainPhoto", photo(db.mainPhoto(s.key)));
        List<Map<String, Object>> photos = new ArrayList<>();
        for (Photo x : db.photos(s.key, null, null, null, false, 100)) {
            if (!Photo.KIND_RESULT.equals(x.kind)) {
                photos.add(photo(x));
            }
        }
        m.put("photos", photos);
        return m;
    }

    /** A setup's calibrated pad types (§22.6 padTypes): counts per status above not-started, and their names. */
    static Map<String, Object> padTypeCounts(List<PadType> types) {
        int validated = 0;
        int characterized = 0;
        int provisional = 0;
        List<String> names = new ArrayList<>();
        for (PadType t : types) {
            if (t.standIn || PadTypeEndpoints.RETIRED.equals(t.family) || t.status == null) {
                continue;
            }
            if (t.status == PadTypeStatus.VALIDATED) {
                validated++;
            }
            else if (t.status == PadTypeStatus.CHARACTERIZED) {
                characterized++;
            }
            else if (t.status == PadTypeStatus.PROVISIONAL) {
                provisional++;
            }
            if (t.status.atLeast(PadTypeStatus.CHARACTERIZED)) {
                names.add(Texts.typeWords(t.family, t.name == null ? t.typeKey : t.name));
            }
        }
        return map("validated", validated, "characterized", characterized, "provisional", provisional, "names",
                names);
    }

    /** Where a dose's dot sizes come from: bench | measured | none (the strip and the check's tip, §22.6). */
    static String dotsSource(Tip t) {
        return t.benchModel ? "bench" : t.isMeasured() ? "measured" : "none";
    }

    /** §22.6 dots of the mounted tip: the bench model's, or its dot table's (masses per turn). */
    static Map<String, Object> dots(Tip t, int stepsPerRev) {
        if (t.benchModel) {
            return benchDots(t.minDotSteps);
        }
        Long at = t.mgPerStepAtMs;
        List<Map<String, Object>> doses = new ArrayList<>();
        for (Map.Entry<Integer, Tip.Measured> e : t.measured.entrySet()) {
            Tip.Measured x = e.getValue();
            doses.add(map("steps", e.getKey(), "diameterMm", new double[] {r3(x.dMeanMm), r3(x.d95Mm)}, "source",
                    "measured", "recordId", x.recordId, "atMs", x.atMs));
            at = at == null || x.atMs > at ? x.atMs : at;
        }
        return map("source", dotsSource(t), "atMs", at, "mgPerTurn", t.mgPerStep == null ? null
                : r3(t.mgPerStep * stepsPerRev), "doses", doses);
    }

    /** As above for a setup that is not mounted: its dot table at its parked recipe, the loaded paste and the flag. */
    static Map<String, Object> dots(DoseRecords.DotTable d, int minDotSteps, int stepsPerRev) {
        Long at = d.mgPerStepAtMs;
        List<Map<String, Object>> doses = new ArrayList<>();
        for (Map.Entry<Integer, Tip.Measured> e : d.doses.entrySet()) {
            Tip.Measured x = e.getValue();
            doses.add(map("steps", e.getKey(), "diameterMm", new double[] {r3(x.dMeanMm), r3(x.d95Mm)}, "source",
                    "measured", "recordId", x.recordId, "atMs", x.atMs));
            at = at == null || x.atMs > at ? x.atMs : at;
        }
        boolean measured = d.doses.containsKey(minDotSteps) && d.mgPerStep != null;
        return map("source", measured ? "measured" : "none", "atMs", at, "mgPerTurn", d.mgPerStep == null ? null
                : r3(d.mgPerStep * stepsPerRev), "doses", doses);
    }

    /** §1's bench model (the seeded 22G bent taper): every allowed dose with its model sizes, 2.11 mg per turn. */
    static Map<String, Object> benchDots(int minDotSteps) {
        List<Map<String, Object>> doses = new ArrayList<>();
        for (int s : DoseModel.ALLOWED_STEPS) {
            if (s >= minDotSteps) {
                doses.add(map("steps", s, "diameterMm", new double[] {r3(DoseModel.dLoMm(s)), r3(DoseModel.dHiMm(s))},
                        "source", "model"));
            }
        }
        return map("source", "bench", "atMs", null, "mgPerTurn", DoseModel.M_REV_MG, "doses", doses);
    }

    /** A needle change as the engine recorded it (§22.3): {atMs, setupKey, setupName, why, voided, text}. */
    static Map<String, Object> needleChange(NeedleChange nc) {
        if (nc == null) {
            return null;
        }
        return map("atMs", nc.atMs, "setupKey", nc.setupKey, "setupName", nc.setupName, "why", nc.why, "operator",
                nc.operator, "voided", map("coupons", nc.voided.coupons, "job", nc.voided.job, "task", nc.voided.task),
                "text", nc.text);
    }

    /**
     * The mount response of §22.3: what is mounted, what it replaced, its recipe, what the needle change voided, what
     * went stale, N2's head offset now against the one the incoming landing was checked under, the next steps, until
     * when it can be undone, and the text.
     */
    static Map<String, Object> mount(MountResult r, NeedleChange nc, List<Map<String, Object>> next, String text) {
        NeedleChange c = nc != null ? nc : r.needleChange;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mounted", r.mounted);
        m.put("mountedName", r.mountedName);
        m.put("from", r.from);
        m.put("fromName", r.fromName);
        m.put("atMs", r.atMs);
        m.put("needleChangedAtMs", r.needleChangedAtMs);
        m.put("profile", r.profile == null ? null : map("name", r.profile.name, "pressurePsi", r.profile.pressurePsi,
                "netStepsPerDot", r.profile.netStepsPerDot, "fingerprint", r.profile.fingerprint()));
        m.put("voided", c == null ? null : map("coupons", c.voided.coupons, "job", c.voided.job, "task",
                c.voided.task));
        m.put("needleChange", needleChange(c));
        m.put("stale", r.stale == null ? new ArrayList<>() : new ArrayList<>(r.stale));
        m.put("headOffset", map("now", r.headOffsetNow, "landingWas", r.headOffsetLandingWas));
        m.put("next", next);
        m.put("undoUntilMs", r.undoUntilMs);
        m.put("warnings", r.warnings == null ? new ArrayList<>() : new ArrayList<>(r.warnings));
        m.put("text", text);
        return m;
    }

    /** "+2.32" / "−0.41": a signed millimetre value as the texts write it (two decimals, a real minus sign). */
    static String signed(double v) {
        String s = String.format(Locale.ROOT, "%.2f", Math.abs(v));
        return (v < -0.005 ? "\u2212" : "+") + s;
    }

    static Double r3(Double x) {
        return x == null ? null : Math.round(x * 1000) / 1000.0;
    }

    /** A number as the texts write it: at most 3 decimals, no trailing zeros ("18", "0.72", "0.8"). */
    static String num(double v) {
        return java.math.BigDecimal.valueOf(Math.round(v * 1000) / 1000.0).stripTrailingZeros().toPlainString();
    }

    static Map<String, Object> calibration(Calibration c) {
        if (c == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id);
        m.put("createdAtMs", c.createdAtMs);
        m.put("method", c.method);
        m.put("status", c.status);
        m.put("qMm3PerRev", c.qMm3PerRev);
        m.put("qCv", c.qCv);
        m.put("qN", c.qN);
        m.put("densityMgMm3", c.densityMgMm3);
        m.put("tip", c.tip);
        m.put("cartridge", c.cartridge);
        m.put("pasteLot", c.pasteLot);
        m.put("setPressurePsi", c.setPressurePsi);
        m.put("bFeedDegS", c.bFeedDegS);
        m.put("barrelTempC", c.barrelTempC);
        m.put("barrelPressurePsi", c.barrelPressurePsi);
        m.put("pasteHoursRt", c.pasteHoursRt);
        m.put("validUntilMs", c.validUntilMs);
        m.put("operator", c.operator);
        m.put("notes", c.notes);
        return m;
    }

    static Map<String, Object> ladderRow(DotLadderRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("calibrationId", r.calibrationId);
        m.put("createdAtMs", r.createdAtMs);
        m.put("rotationDeg", r.rotationDeg);
        m.put("zGapMm", r.zGapMm);
        m.put("n", r.n);
        m.put("diameterMeanMm", r.diameterMeanMm);
        m.put("diameterCv", r.diameterCv);
        m.put("areaMeanMm2", r.areaMeanMm2);
        m.put("areaCv", r.areaCv);
        m.put("mgPerDotMean", r.mgPerDotMean);
        m.put("mgPerDotSe", r.mgPerDotSe);
        m.put("heightMm", r.heightMm);
        m.put("method", r.method);
        m.put("notes", r.notes);
        return m;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
