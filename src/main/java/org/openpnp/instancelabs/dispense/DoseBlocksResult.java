package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.instancelabs.vision.Dot;

/** Outcome of {@link DispenseService#runDoseBlocks} (design §7.3), Gson-friendly except the crops' bytes. */
public final class DoseBlocksResult {
    public RunResult run;
    public boolean ok;
    public String message;
    public HomeReport home;
    public ZCheck.Result zCheck;
    /** home | z check | before images | dispensing | after images, when ABORTED. */
    public String stoppedStage;
    /** Marlin step count of B at the start of the program, or null. */
    public Long bCountAtStart;
    public final List<BlockOut> blocks = new ArrayList<>();
    public final List<Crop> crops = new ArrayList<>();
    /** "x,y: <BareSpotCheck reason>" of the spots the before frames refused. */
    public final List<String> refusedSpots = new ArrayList<>();
    /** Before and after frames taken (camera captures). */
    public int frames;

    /** One block as laid and seen. */
    public static final class BlockOut {
        public int netSteps;
        public String label;
        public String couponPlace;
        public double hopMm;
        public final List<DotOut> dots = new ArrayList<>();

        public int found() {
            int n = 0;
            for (DotOut d : dots) {
                n += d.found != null ? 1 : 0;
            }
            return n;
        }
    }

    /** One commanded dot: where it was meant to go, its start count and screw phase, what the camera found. */
    public static final class DotOut {
        public double x, y;
        public Long startCount;
        public Integer phase;
        /** The new blob nearest the spot within the block's match radius (offsets from the spot), or null. */
        public Dot found;
        /** Landing: found - commanded (mm). */
        public Double dxMm, dyMm;
        /** A paste thread joins this dot and the next of its block (the corridor test, §7.4). */
        public boolean threadToNext;
        /** Not round (circularity below 0.35): a string. */
        public boolean string;
    }

    /** 3 x 3 mm around a spot, before and after, 96 px PNG (the result card). */
    public static final class Crop {
        public String id;
        public int block;
        public int dot;
        /** first | median | worst */
        public String what;
        public transient byte[] beforePng;
        public transient byte[] afterPng;
    }
}
