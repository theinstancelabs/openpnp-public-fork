package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * One stored picture (tip setups design §22.4): a row of {@code photos} with its file and what it shows.
 * Plain fields, Gson-friendly; {@link #links}, {@link #removedAtMs} and {@link #removedWhy} are filled
 * when read from {@code photo_links} and {@code photo_events}.
 */
public class Photo {
    /** A picture of the tip taken for a calibration or from Records. */
    public static final String KIND_TIP = "tip";
    /** A picture of the setup (added with it, or from the Setup card). */
    public static final String KIND_SETUP = "setup";
    /** A job's top-camera photo of its test dots: the calibration's evidence, never removed. */
    public static final String KIND_RESULT = "result";
    public static final String SOURCE_BOTTOM_CAMERA = "bottom-camera";
    public static final String SOURCE_TOP_CAMERA = "top-camera";
    public static final String SOURCE_UPLOAD = "upload";
    /** photo_links roles: the record's result photo, a picture of the tip taken for it, its setup's main picture. */
    public static final String ROLE_RESULT = "result";
    public static final String ROLE_TIP = "tip";
    public static final String ROLE_REFERENCE = "reference";
    /** photo_events actions; the newest remove/restore row of a photo decides. */
    public static final String ACTION_REMOVE = "remove";
    public static final String ACTION_RESTORE = "restore";
    public static final String ACTION_MAIN = "main";

    public long id;
    public long createdAtMs;
    /** tip | setup | result. */
    public String kind;
    /** bottom-camera | top-camera | upload. */
    public String source;
    public String setupKey;
    /** The needle token it was taken after (§22.3); a bottom-camera picture links only to records of that needle. */
    public Long needleSinceMs;
    public String jobId;
    /** Result photos: the dose of their block (steps). */
    public Integer doseSteps;
    /** Result photos of weigh blocks: the coupon place. */
    public String couponPlace;
    /** Path under the photos folder: {@code <yyyy-MM>/<first 20 hex of the SHA-256>.jpg}. */
    public String file;
    public String sha256;
    /** Size of the stored JPEG. */
    public long bytes;
    public int widthPx, heightPx;
    /** The camera's scale (information only); null for uploads. */
    public Double mmPerPx;
    /** Machine X/Y of the frame centre (result photos). */
    public Double xMm, yMm;
    public String caption;
    public boolean simulated;
    public String operator;
    /** The pad calibration records it is linked to. */
    public List<Long> links = new ArrayList<>();
    /** When and why it was removed (null = live). */
    public Long removedAtMs;
    public String removedWhy;

    /** A row of {@code photo_links}: a photo shown with a calibration record. */
    public static class Link {
        public long id;
        public long createdAtMs;
        public long photoId;
        /** The {@code pad_calibrations} row. */
        public long calibrationId;
        /** result | tip | reference. */
        public String role;
        public String operator;
    }

    /** A row of {@code photo_events}: removed, brought back, or chosen as its setup's main picture. */
    public static class Event {
        public long id;
        public long createdAtMs;
        public long photoId;
        /** remove | restore | main. */
        public String action;
        public String why;
        public String operator;
    }
}
