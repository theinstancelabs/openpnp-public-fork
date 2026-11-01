package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Output of the planner: ordered dots in N2 machine coordinates (the coordinates OpenPnP would move
 * the N2 needle tip to), the pads they belong to and the dispense profile they were planned with
 * (every dot is one profile dot). Raw controller coordinates are derived from these at stream time
 * through OpenPnP's transform chain.
 */
public class DispensePlan {
    public static class PlannedPad {
        public String padId;
        public String placementId;
        public String packageId;
        public String typeKey;
        public int typeVersion;
        public PadTypeStatus typeStatus;
        public PadGeometry.Source source;
        public double xMm, yMm, rotationDeg, wMm, hMm, surfaceZMm;
        public double areaMm2;
        public double tMm;
        public double k;
        /** Paste a stencil of thickness t would print (A x t x k): informational, not a dose. */
        public double targetVolumeMm3;
        public double targetMassMg;
        public int dotCount;
        /** Net screw turn per dot (deg of B) from the profile. */
        public Double rotationPerDotDeg;
        public final EnumSet<PadFlag> flags = EnumSet.noneOf(PadFlag.class);

        public boolean isBlocked() {
            for (PadFlag f : flags) {
                if (f.blocking) {
                    return true;
                }
            }
            return false;
        }

        public List<String> flagNames() {
            List<String> l = new ArrayList<>();
            for (PadFlag f : flags) {
                l.add(f.name());
            }
            return l;
        }
    }

    public static class PlannedDot {
        /** Position in execution order (0-based). */
        public int index;
        public String padId;
        /** N2 needle-tip machine coordinates (mm). */
        public double xMm, yMm;
        /** Surface Z under the dot. */
        public double surfaceZMm;
        /** N2 Z while dispensing = surface + needle Z offset + gap. */
        public double dispenseZMm;
        /** N2 Z between dots on the board = dispense Z + hop height. */
        public double hopZMm;
        /** Net auger rotation for this dot (deg of B, always positive = extrude): the profile's. */
        public double rotationDeg;
    }

    public String planId;
    public String boardName;
    public String boardLocationId;
    public long createdAtMs;
    /** The dispense profile the plan was made with, and its identity (a run refuses a stale plan). */
    public DispenseProfile profile;
    public String profileFingerprint;
    public double needleGapMm;
    public double needleZOffsetMm;
    public double hopHeightMm;
    /** Identity of the board surface map the dot Z came from (null: none, single board Z). */
    public String surfaceFingerprint;
    /** Identity of the board location, fiducial transform and placements at planning time. */
    public String boardFingerprint;
    /** Identity of the board registration only (X/Y/rotation, side, fiducial transform): I4 surface tie. */
    public String boardRegistration;
    /** Identity of the pad types and package mappings the pads were resolved with ({@link PadTypeResolver#fingerprint}). */
    public String padTypesFingerprint;
    public final List<PlannedPad> pads = new ArrayList<>();
    public final List<PlannedDot> dots = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
    public double pathLengthNearestNeighbourMm;
    public double pathLengthMm;

    public List<PlannedPad> blockedPads() {
        List<PlannedPad> l = new ArrayList<>();
        for (PlannedPad p : pads) {
            if (p.isBlocked()) {
                l.add(p);
            }
        }
        return l;
    }

    public double totalRotationDeg() {
        double s = 0;
        for (PlannedDot d : dots) {
            s += d.rotationDeg;
        }
        return s;
    }

    public String summary() {
        return String.format("plan %s board %s: %d pads (%d blocked), %d dots, path %.1f mm (NN %.1f), "
                + "profile '%s' %s", planId, boardName, pads.size(), blockedPads().size(), dots.size(),
                pathLengthMm, pathLengthNearestNeighbourMm, profile == null ? "-" : profile.name,
                profileFingerprint);
    }
}
