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
    /**
     * One result photo per block (tip setups design §22.4): the after frame of its first tile, at most 1600 px wide,
     * as JPEG bytes (never a BufferedImage); the job stores them when the step ends and drops them.
     */
    public final List<BlockPhoto> photos = new ArrayList<>();
    /**
     * The tip setup and the needle token the step ran with (§22.3, §22.4): its result photos are of that setup and
     * needle, whatever is mounted when the job stores them; null before the step ran.
     */
    public String setupKey;
    public Long needleSinceMs;
    /** Every new blob of the after frames in machine mm (the landing check's locator and fit, §22.3). */
    public final List<Blob> newBlobs = new ArrayList<>();

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

    /** The after frame of a block's first tile as a JPEG (§22.4: kind result, source top-camera). */
    public static final class BlockPhoto {
        /** Index of the block in the request, its dose and coupon place (weigh blocks). */
        public int block;
        public int doseSteps;
        public String couponPlace;
        /** Machine X/Y of the frame centre and its scale after scaling (mm, mm/px). */
        public double xMm, yMm;
        public double mmPerPx;
        public int widthPx, heightPx;
        public transient byte[] jpeg;
    }

    /** A new blob between a before and an after frame, in machine coordinates. */
    public static final class Blob {
        /** Machine X/Y of its centroid (mm). */
        public double x, y;
        public double diameterMm, areaMm2, circularity;
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
