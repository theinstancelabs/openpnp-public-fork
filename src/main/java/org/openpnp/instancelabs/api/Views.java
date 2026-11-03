package org.openpnp.instancelabs.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openpnp.instancelabs.dispense.Calibration;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.instancelabs.dispense.DispensePlan;
import org.openpnp.instancelabs.dispense.DispenseProfile;
import org.openpnp.instancelabs.dispense.DispenseService;
import org.openpnp.instancelabs.dispense.BoardRegistration;
import org.openpnp.instancelabs.dispense.DotLadderRow;
import org.openpnp.instancelabs.dispense.HomeReport;
import org.openpnp.instancelabs.dispense.HomeTracker;
import org.openpnp.instancelabs.dispense.InterlockReport;
import org.openpnp.instancelabs.dispense.PadCleanReport;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PlaneReport;
import org.openpnp.instancelabs.dispense.RegistrationResult;
import org.openpnp.instancelabs.dispense.RunResult;
import org.openpnp.instancelabs.dispense.SurfaceProbeResult;
import org.openpnp.instancelabs.dispense.VacuumProber;

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
        return m;
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
