package org.openpnp.instancelabs.dispense;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * The pictures (tip setups design §22.4). Every photo is re-encoded by the engine (JPEG quality 0.85, at
 * most 2048 px on the long side, no metadata) with a 320 px thumbnail, written through a temporary file
 * in its month folder and moved to {@code <configDir>/instancelabs/photos/<yyyy-MM>/<first 20 hex of the
 * JPEG's SHA-256>.jpg} (and {@code .thumb.jpg}); equal pictures share a file. One row of {@code photos}
 * each. The decode reads the header first and subsamples, never the full raster; alpha and indexed
 * images are drawn onto RGB. Removal keeps the file 7 days; the start sweep deletes what no live row
 * names. Synchronized: one picture is stored at a time.
 */
public final class PhotoStore {
    /** The folder under {@code <configDir>/instancelabs}. */
    public static final String SUBDIR = "photos";
    /** The upload route's request body limit (413 above it). */
    public static final int MAX_REQUEST_BYTES = 8 * 1024 * 1024;
    /** A side of at least 64 and at most 12,000 px, at most 40 Mpx, read from the header before decoding. */
    public static final int MIN_SIDE_PX = 64, MAX_SIDE_PX = 12_000;
    public static final long MAX_PIXELS = 40_000_000L;
    /** The stored picture's long side and the thumbnail's (px), the JPEG quality. */
    public static final int LONG_SIDE_PX = 2048, THUMB_PX = 320;
    public static final float JPEG_QUALITY = 0.85f;
    /** A result photo (one per dose block) is scaled to at most this width before it leaves the machine thread. */
    public static final int RESULT_MAX_WIDTH_PX = 1600;
    /** At most 6 tip pictures per calibration record and 20 pictures of a setup not linked to a calibration. */
    public static final int MAX_TIP_PER_RECORD = 6, MAX_UNLINKED_PER_SETUP = 20;
    /** Removable pictures (tip and setup) take at most this; result photos are not counted (they are pruned). */
    public static final long DISK_CAP_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int MAX_CAPTION = 200;
    /** A removed picture's file stays this long (restore); an unlinked result photo this long. */
    public static final long KEEP_REMOVED_MS = 7L * 24 * 3600 * 1000, UNLINKED_RESULT_MS = 30L * 24 * 3600 * 1000;

    /** What to store: the bytes of a JPEG or PNG (or a frame the engine took), what it shows and where it goes. */
    public static final class Input {
        /** JPEG or PNG bytes (an upload, a result photo); checked by their first bytes, then decoded. */
        public byte[] bytes;
        /** Instead of bytes: a frame the engine took itself (the tip picture). */
        public transient BufferedImage image;
        /** tip | setup | result ({@link Photo}). */
        public String kind;
        /** bottom-camera | top-camera | upload. */
        public String source;
        public String setupKey;
        public Long needleSinceMs;
        public String jobId;
        public Integer doseSteps;
        public String couponPlace;
        public Double mmPerPx;
        public Double xMm, yMm;
        /** At most {@link #MAX_CAPTION} characters, control characters removed. */
        public String caption;
        /** Records to link the photo to (role tip for tip pictures; the setup must match, §22.4). */
        public List<Long> calibrationIds = new ArrayList<>();
        public boolean simulated;
        public String operator;
    }

    private final File folder;
    private final DispenseDb db;
    /**
     * One upload pipeline at a time (§22.4: header, decode, encode, store), taken without waiting by
     * {@link #storeExclusive}: the API's thread pool is unbounded, so this also bounds memory.
     */
    public static final Semaphore PIPELINE = new Semaphore(1);

    /**
     * @param folder {@link #folderFor} the configuration directory
     */
    public PhotoStore(File folder, DispenseDb db) {
        this.folder = folder;
        this.db = db;
    }

    /** {@code <configDir>/instancelabs/photos}. */
    public static File folderFor(File configDir) {
        return new File(new File(configDir, DispenseConfig.SUBDIR), SUBDIR);
    }

    /** The folder the pictures are kept in. */
    public File folder() {
        return folder;
    }

    /**
     * As {@link #store} when no other picture is being stored, else 409 {@code busy} ("Another picture is being stored:
     * try again in a moment."): the upload route's pipeline, never waiting (§22.4).
     */
    public Photo storeExclusive(Input in) throws IOException, SQLException {
        if (!PIPELINE.tryAcquire()) {
            throw new EngineRefusal(409, "busy", "Another picture is being stored: try again in a moment.");
        }
        try {
            return store(in);
        }
        finally {
            PIPELINE.release();
        }
    }

    /**
     * Checks, decodes, re-encodes and stores a picture and its row (and its links): 400 {@code not-an-image} or the
     * pixel and caption texts, 409 {@code too-many} and {@code disk}, as {@link EngineRefusal} (§22.4 limits).
     */
    public synchronized Photo store(Input in) throws IOException, SQLException {
        if (!Photo.KIND_TIP.equals(in.kind) && !Photo.KIND_SETUP.equals(in.kind) && !Photo.KIND_RESULT.equals(in.kind)) {
            throw new IllegalArgumentException("kind must be tip, setup or result");
        }
        if (!Photo.SOURCE_BOTTOM_CAMERA.equals(in.source) && !Photo.SOURCE_TOP_CAMERA.equals(in.source)
                && !Photo.SOURCE_UPLOAD.equals(in.source)) {
            throw new IllegalArgumentException("source must be bottom-camera, top-camera or upload");
        }
        TipSetup setup = in.setupKey == null ? null : db.currentTipSetup(in.setupKey);
        if (setup == null) {
            throw new EngineRefusal(404, "not-found", "There is no tip " + in.setupKey + ".");
        }
        String caption = caption(in.caption);
        List<Long> calibrations = in.calibrationIds == null ? new ArrayList<>() : in.calibrationIds;
        for (Long id : calibrations) {
            checkLink(db, in.setupKey, in.source, in.needleSinceMs, id);
        }
        boolean removable = !Photo.KIND_RESULT.equals(in.kind);
        if (removable) {
            limits(setup, in.kind, calibrations);
        }
        BufferedImage img = in.image != null ? in.image : decode(in.bytes);
        img = rgb(scaleTo(img, LONG_SIDE_PX));
        byte[] jpeg = jpeg(img);
        byte[] thumb = jpeg(rgb(scaleTo(img, THUMB_PX)));
        String sha = sha256(jpeg);
        String month = new SimpleDateFormat("yyyy-MM", Locale.ROOT).format(new Date());
        File dir = new File(folder, month);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("the photo folder " + dir + " cannot be made");
        }
        String name = sha.substring(0, 20);
        File file = new File(dir, name + ".jpg");
        File thumbFile = new File(dir, name + ".thumb.jpg");
        put(dir, file, jpeg);
        put(dir, thumbFile, thumb);
        Photo p = new Photo();
        p.kind = in.kind;
        p.source = in.source;
        p.setupKey = in.setupKey;
        p.needleSinceMs = in.needleSinceMs;
        p.jobId = in.jobId;
        p.doseSteps = in.doseSteps;
        p.couponPlace = in.couponPlace;
        p.file = month + "/" + name + ".jpg";
        p.sha256 = sha;
        p.bytes = jpeg.length;
        p.widthPx = img.getWidth();
        p.heightPx = img.getHeight();
        p.mmPerPx = in.mmPerPx;
        p.xMm = in.xMm;
        p.yMm = in.yMm;
        p.caption = caption;
        p.simulated = in.simulated;
        p.operator = in.operator;
        Photo stored = db.insertPhoto(p);
        for (Long id : calibrations) {
            db.insertPhotoLink(stored.id, id, Photo.KIND_RESULT.equals(in.kind) ? Photo.ROLE_RESULT : Photo.ROLE_TIP,
                    in.operator);
        }
        return db.photo(stored.id);
    }

    /** The caption cleaned: control characters removed, at most {@link #MAX_CAPTION} characters (else 400). */
    static String caption(String c) {
        if (c == null) {
            return null;
        }
        String s = c.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        if (s.length() > MAX_CAPTION) {
            throw new EngineRefusal(400, "caption", "The caption must be at most " + MAX_CAPTION + " characters.");
        }
        return s.isEmpty() ? null : s;
    }

    /**
     * A photo may be linked to a calibration record (§22.4): the record exists and was made with the photo's setup
     * (409 {@code setup-mismatch}); a bottom-camera picture only to a record of the same needle (409
     * {@code needle-changed}). A record without a needle token (no change recorded before it, or made before schema
     * 3) is of the needle before any change made after it.
     */
    public static PadCalibrationRecord checkLink(DispenseDb db, String setupKey, String source, Long needleSinceMs,
            long calibrationId) throws SQLException {
        PadCalibrationRecord r = db.padCalibration(calibrationId);
        if (r == null) {
            throw new EngineRefusal(404, "not-found", "There is no calibration " + calibrationId + ".");
        }
        if (!setupKey.equals(r.setupKey)) {
            throw new EngineRefusal(409, "setup-mismatch", "This picture is of the " + nameOf(db, setupKey)
                    + "; calibration " + calibrationId + " was made with the " + nameOf(db, r.setupKey) + ".");
        }
        boolean sameNeedle = r.needleSinceMs != null ? r.needleSinceMs.equals(needleSinceMs)
                : needleSinceMs == null || needleSinceMs <= r.createdAtMs;
        if (Photo.SOURCE_BOTTOM_CAMERA.equals(source) && !sameNeedle) {
            throw new EngineRefusal(409, "needle-changed", "This picture was taken with another needle than calibration "
                    + calibrationId + ": a needle change came between them.");
        }
        return r;
    }

    private static String nameOf(DispenseDb db, String key) throws SQLException {
        TipSetup s = key == null ? null : db.currentTipSetup(key);
        return s == null ? String.valueOf(key) : s.name;
    }

    /** The counts and the disk cap of removable pictures (§22.4). */
    private void limits(TipSetup setup, String kind, List<Long> calibrations) throws SQLException {
        if (Photo.KIND_TIP.equals(kind)) {
            for (Long id : calibrations) {
                checkTipCount(db, id, 1);
            }
        }
        if (calibrations.isEmpty()) {
            int unlinked = 0;
            for (Photo p : db.photos(setup.key, null, null, null, false, 100000)) {
                if (!Photo.KIND_RESULT.equals(p.kind) && p.links.isEmpty()) {
                    unlinked++;
                }
            }
            if (unlinked >= MAX_UNLINKED_PER_SETUP) {
                throw new EngineRefusal(409, "too-many", "The " + setup.name + " has " + MAX_UNLINKED_PER_SETUP
                        + " pictures already: remove one first.");
            }
        }
        long used = removableBytes();
        if (used >= DISK_CAP_BYTES) {
            throw new EngineRefusal(409, "disk", String.format(Locale.ROOT, "The pictures take %.1f GB already: remove "
                    + "old ones first.", used / (1024.0 * 1024 * 1024)));
        }
    }

    /**
     * At most {@link #MAX_TIP_PER_RECORD} pictures of the tip per calibration record (§22.4), whether stored with the
     * record or linked to it later: 409 {@code too-many} when {@code adding} more would pass it.
     */
    public static void checkTipCount(DispenseDb db, long calibrationId, int adding) throws SQLException {
        int n = 0;
        for (Photo p : db.photos(null, calibrationId, null, Photo.KIND_TIP, false, 1000)) {
            n += p.links.contains(calibrationId) ? 1 : 0;
        }
        if (adding > 0 && n + adding > MAX_TIP_PER_RECORD) {
            throw new EngineRefusal(409, "too-many", "Calibration " + calibrationId + " has " + MAX_TIP_PER_RECORD
                    + " pictures of the tip already: remove one first.");
        }
    }

    /**
     * The bytes of the live removable pictures' files (tip and setup), each file once. A removed picture leaves the
     * count at once, so "remove old ones first" makes room; its file leaves the disk at the start sweep after its 7
     * days of undo (rows of removed pictures never count, or the cap could never be got below).
     */
    long removableBytes() throws SQLException {
        Map<String, Long> files = new HashMap<>();
        for (Map<String, Object> m : db.query("SELECT p.file, p.bytes FROM photos p WHERE p.kind IN ('tip', 'setup')"
                + " AND COALESCE((SELECT e.action FROM photo_events e WHERE e.photo_id = p.id AND e.action IN"
                + " ('remove', 'restore') ORDER BY e.id DESC LIMIT 1), 'restore') = 'restore'")) {
            files.put((String) m.get("file"), ((Number) m.get("bytes")).longValue());
        }
        long n = 0;
        for (long b : files.values()) {
            n += b;
        }
        return n;
    }

    /**
     * The picture of a JPEG or PNG: the type by its first bytes (400 {@code not-an-image}), the size from the header
     * before decoding (400), then decoded subsampled so the raster is never larger than twice the stored size a side.
     */
    static BufferedImage decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw notAnImage();
        }
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new EngineRefusal(413, "too-large", "The picture is larger than 8 MB: send a smaller one (phones can "
                    + "share a reduced size).");
        }
        boolean jpeg = bytes.length > 3 && (bytes[0] & 0xff) == 0xFF && (bytes[1] & 0xff) == 0xD8
                && (bytes[2] & 0xff) == 0xFF;
        boolean png = bytes.length > 4 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G';
        if (!jpeg && !png) {
            throw notAnImage();
        }
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(jpeg ? "jpeg" : "png");
            if (!it.hasNext()) {
                throw notAnImage();
            }
            ImageReader r = it.next();
            try {
                r.setInput(iis, true, true);
                int w = r.getWidth(0), h = r.getHeight(0);
                if (w < MIN_SIDE_PX || h < MIN_SIDE_PX || w > MAX_SIDE_PX || h > MAX_SIDE_PX) {
                    throw new EngineRefusal(400, "size", String.format(Locale.ROOT, "The picture is %d × %d px: at "
                            + "least %d and at most 12,000 px a side.", w, h, MIN_SIDE_PX));
                }
                if ((long) w * h > MAX_PIXELS) {
                    // its sides are within the limits: say the limit it breaks
                    throw new EngineRefusal(400, "size", String.format(Locale.ROOT, "The picture is %d × %d px (%.0f "
                            + "Mpx): at most %d Mpx. Send a smaller one (phones can share a reduced size).", w, h,
                            w * (double) h / 1e6, MAX_PIXELS / 1_000_000));
                }
                ImageReadParam param = r.getDefaultReadParam();
                int n = Math.max(1, Math.max(w, h) / LONG_SIDE_PX);
                if (n > 1) {
                    param.setSourceSubsampling(n, n, 0, 0);
                }
                BufferedImage img = r.read(0, param);
                if (img == null) {
                    throw notAnImage();
                }
                return img;
            }
            finally {
                r.dispose();
            }
        }
        catch (EngineRefusal e) {
            throw e;
        }
        catch (IOException | RuntimeException e) {
            throw notAnImage();
        }
    }

    private static EngineRefusal notAnImage() {
        return new EngineRefusal(400, "not-an-image", "This file is not a JPEG or PNG picture.");
    }

    /** The picture scaled down (never up) so its long side is at most {@code longSide} px. */
    static BufferedImage scaleTo(BufferedImage img, int longSide) {
        int w = img.getWidth(), h = img.getHeight();
        int l = Math.max(w, h);
        if (l <= longSide) {
            return img;
        }
        int nw = Math.max(1, (int) Math.round(w * (double) longSide / l));
        int nh = Math.max(1, (int) Math.round(h * (double) longSide / l));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
        }
        finally {
            g.dispose();
        }
        return out;
    }

    /** An RGB copy of an image with alpha, a palette or another layout (the JPEG writer refuses alpha). */
    static BufferedImage rgb(BufferedImage img) {
        if (img.getType() == BufferedImage.TYPE_INT_RGB || img.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return img;
        }
        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
        }
        finally {
            g.dispose();
        }
        return out;
    }

    /** JPEG of quality {@link #JPEG_QUALITY}, no metadata but the JFIF header. */
    static byte[] jpeg(BufferedImage img) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(JPEG_QUALITY);
            w.write(null, new IIOImage(img, null, null), p);
        }
        finally {
            w.dispose();
        }
        return out.toByteArray();
    }

    static String sha256(byte[] b) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : h) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a file through a temporary file in its own folder (one file system: an atomic move); kept if it exists. */
    private static void put(File dir, File target, byte[] bytes) throws IOException {
        if (target.isFile()) {
            return; // content-addressed: an equal picture shares the file
        }
        File tmp = File.createTempFile(".photo-", ".tmp", dir);
        try {
            Files.write(tmp.toPath(), bytes);
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            catch (java.nio.file.FileAlreadyExistsException e) {
                // another store wrote the same picture meanwhile
            }
        }
        finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /** The photo's file, resolved under the folder; a path that leaves it throws IllegalStateException. */
    public File file(Photo p) {
        return resolve(p.file);
    }

    /** The photo's 320 px thumbnail, resolved as {@link #file}. */
    public File thumbFile(Photo p) {
        return resolve(p.file.endsWith(".jpg") ? p.file.substring(0, p.file.length() - 4) + ".thumb.jpg"
                : p.file + ".thumb.jpg");
    }

    private File resolve(String rel) {
        try {
            File base = folder.getCanonicalFile();
            File f = new File(base, rel).getCanonicalFile();
            if (!f.toPath().startsWith(base.toPath()) || f.equals(base)) {
                throw new IllegalStateException("photo file outside its folder");
            }
            return f;
        }
        catch (IOException e) {
            throw new IllegalStateException("photo file outside its folder", e);
        }
    }

    /**
     * Removes a tip or setup picture (a {@code remove} event; the file stays {@link #KEEP_REMOVED_MS}); a result
     * photo throws {@link EngineRefusal} 409 ("A result photo is the calibration's evidence: it stays.").
     */
    public synchronized Photo remove(long id, String why, String operator) throws SQLException {
        Photo p = require(id);
        if (Photo.KIND_RESULT.equals(p.kind)) {
            throw new EngineRefusal(409, "result-photo", "A result photo is the calibration's evidence: it stays.");
        }
        if (p.removedAtMs == null) {
            db.insertPhotoEvent(id, Photo.ACTION_REMOVE, why == null || why.trim().isEmpty() ? null
                    : caption(why), operator);
        }
        return db.photo(id);
    }

    /** Brings a removed picture back (a {@code restore} event); 410 when its file is gone. */
    public synchronized Photo restore(long id, String operator) throws SQLException {
        Photo p = require(id);
        if (!file(p).isFile()) {
            throw new EngineRefusal(410, "gone", "The picture's file is gone: it cannot be brought back.");
        }
        if (p.removedAtMs != null) {
            db.insertPhotoEvent(id, Photo.ACTION_RESTORE, null, operator);
        }
        return db.photo(id);
    }

    /** Chooses a picture as its setup's main picture (a {@code main} event, "Use as the tip's picture"). */
    public synchronized Photo setMain(long id, String operator) throws SQLException {
        Photo p = require(id);
        if (Photo.KIND_RESULT.equals(p.kind)) {
            throw new EngineRefusal(409, "result-photo", "A result photo shows the dots, not the tip: choose a picture of "
                    + "the tip.");
        }
        if (p.removedAtMs != null) {
            throw new EngineRefusal(410, "removed", "The picture was removed: bring it back first.");
        }
        db.insertPhotoEvent(id, Photo.ACTION_MAIN, null, operator);
        return db.photo(id);
    }

    private Photo require(long id) throws SQLException {
        Photo p = db.photo(id);
        if (p == null) {
            throw new EngineRefusal(404, "not-found", "There is no picture " + id + ".");
        }
        return p;
    }

    /**
     * The start sweep: deletes files no live row names (a crash between the move and the insert), files of photos
     * removed more than 7 days ago and result photos never linked after 30 days. Returns the files deleted.
     */
    public synchronized int sweep(long nowMs) throws SQLException {
        File base = folder;
        if (!base.isDirectory()) {
            return 0;
        }
        Set<String> keep = new HashSet<>();
        for (Map<String, Object> m : db.query("SELECT id FROM photos")) {
            Photo p = db.photo(((Number) m.get("id")).longValue());
            boolean removedLong = p.removedAtMs != null && nowMs - p.removedAtMs > KEEP_REMOVED_MS;
            boolean unlinkedResult = Photo.KIND_RESULT.equals(p.kind) && p.links.isEmpty()
                    && nowMs - p.createdAtMs > UNLINKED_RESULT_MS;
            if (!removedLong && !unlinkedResult) {
                keep.add(p.file);
            }
        }
        int deleted = 0;
        File[] months = base.listFiles(File::isDirectory);
        for (File month : months == null ? new File[0] : months) {
            File[] files = month.listFiles(File::isFile);
            for (File f : files == null ? new File[0] : files) {
                String n = f.getName();
                String rel = month.getName() + "/" + (n.endsWith(".thumb.jpg")
                        ? n.substring(0, n.length() - ".thumb.jpg".length()) + ".jpg" : n);
                boolean temp = n.startsWith(".photo-") && nowMs - f.lastModified() > 3600_000L;
                if (temp || !n.startsWith(".photo-") && !keep.contains(rel)) {
                    if (f.delete()) {
                        deleted++;
                    }
                }
            }
        }
        return deleted;
    }
}
