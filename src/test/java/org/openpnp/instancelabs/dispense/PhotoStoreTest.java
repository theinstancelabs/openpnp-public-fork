package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Tip setups (§22.4, §22.8): the picture store. JPEG and PNG are taken (alpha and palettes drawn onto RGB), anything
 * else refused by its bytes; a header too large is refused before decoding and a large picture is decoded
 * subsampled; the stored JPEG has no metadata, at most 2048 px and a 320 px thumbnail; equal pictures share a file;
 * removal keeps the file 7 days, restore, the start sweep; the limits.
 */
public class PhotoStoreTest {
    static byte[] encode(BufferedImage img, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out), format);
        return out.toByteArray();
    }

    static BufferedImage picture(int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(40, 120, 200, 200));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.ORANGE);
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        return img;
    }

    static PhotoStore.Input upload(byte[] bytes) {
        PhotoStore.Input in = new PhotoStore.Input();
        in.bytes = bytes;
        in.kind = Photo.KIND_SETUP;
        in.source = Photo.SOURCE_UPLOAD;
        in.setupKey = Tip.SEED_KEY;
        return in;
    }

    static PhotoStore store(DispenseDb db) throws Exception {
        return new PhotoStore(Files.createTempDirectory("photos").toFile(), db);
    }

    static BufferedImage stored(PhotoStore s, Photo p) throws Exception {
        return ImageIO.read(s.file(p));
    }

    @Test
    public void jpegAndPngAreTakenAlphaAndPalettesAsRgb() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            Photo jpg = s.store(upload(encode(picture(300, 200, BufferedImage.TYPE_INT_RGB), "jpg")));
            assertEquals(300, jpg.widthPx);
            assertEquals(200, jpg.heightPx);
            assertTrue(s.file(jpg).isFile());
            assertTrue(jpg.file.matches("\\d{4}-\\d{2}/[0-9a-f]{20}\\.jpg"), jpg.file);
            assertEquals(64, jpg.sha256.length());
            assertEquals(Files.size(s.file(jpg).toPath()), jpg.bytes);
            Photo rgba = s.store(upload(encode(picture(200, 150, BufferedImage.TYPE_INT_ARGB), "png")));
            BufferedImage back = stored(s, rgba);
            assertEquals(3, back.getColorModel().getNumComponents(), "RGB, no alpha");
            byte[] r = {0, (byte) 255, 0}, g = {0, 0, (byte) 255}, b = {(byte) 255, 0, 0};
            BufferedImage indexed = new BufferedImage(120, 90, BufferedImage.TYPE_BYTE_INDEXED,
                    new IndexColorModel(2, 3, r, g, b));
            Graphics2D gr = indexed.createGraphics();
            gr.setColor(Color.RED);
            gr.fillRect(10, 10, 50, 50);
            gr.dispose();
            Photo idx = s.store(upload(encode(indexed, "png")));
            assertEquals(3, stored(s, idx).getColorModel().getNumComponents());
            assertTrue(s.thumbFile(idx).isFile());
        }
    }

    @Test
    public void anythingElseIsRefusedByItsBytes() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            EngineRefusal gif = assertThrows(EngineRefusal.class,
                    () -> s.store(upload(encode(picture(100, 100, BufferedImage.TYPE_INT_RGB), "gif"))));
            assertEquals("not-an-image", gif.code);
            assertEquals(400, gif.status);
            assertEquals("This file is not a JPEG or PNG picture.", gif.getMessage());
            byte[] garbage = new byte[2000];
            new java.util.Random(1).nextBytes(garbage);
            assertEquals("not-an-image", assertThrows(EngineRefusal.class, () -> s.store(upload(garbage))).code);
            byte[] fakeJpeg = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01}, 500);
            assertEquals("not-an-image", assertThrows(EngineRefusal.class, () -> s.store(upload(fakeJpeg))).code);
            assertEquals(0, db.photos(null, null, null, null, true, 100).size());
        }
    }

    /** A PNG of only its header: {@code w} × {@code h} px (nothing to decode). */
    static byte[] pngHeader(int w, int h) {
        ByteBuffer ihdr = ByteBuffer.allocate(17);
        ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII)).putInt(w).putInt(h).put((byte) 8).put((byte) 2)
                .put((byte) 0).put((byte) 0).put((byte) 0);
        CRC32 crc = new CRC32();
        crc.update(ihdr.array());
        ByteBuffer out = ByteBuffer.allocate(8 + 4 + 17 + 4 + 12);
        out.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}).putInt(13).put(ihdr.array())
                .putInt((int) crc.getValue());
        CRC32 end = new CRC32();
        end.update("IEND".getBytes(StandardCharsets.US_ASCII));
        out.putInt(0).put("IEND".getBytes(StandardCharsets.US_ASCII)).putInt((int) end.getValue());
        return out.array();
    }

    @Test
    public void theSizeIsReadFromTheHeaderBeforeDecoding() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.store(upload(pngHeader(20000, 100))));
            assertEquals(400, e.status);
            assertEquals("The picture is 20000 × 100 px: at least 64 and at most 12,000 px a side.", e.getMessage());
            assertEquals("The picture is 40 × 40 px: at least 64 and at most 12,000 px a side.",
                    assertThrows(EngineRefusal.class, () -> s.store(upload(encode(picture(40, 40,
                            BufferedImage.TYPE_INT_RGB), "png")))).getMessage());
            // over 40 Mpx with both sides allowed
            assertEquals(400, assertThrows(EngineRefusal.class, () -> s.store(upload(pngHeader(7000, 6000)))).status);
        }
    }

    @Test
    public void aLargePictureIsDecodedSubsampledAndStoredAt2048() throws Exception {
        byte[] big = encode(picture(6000, 4000, BufferedImage.TYPE_3BYTE_BGR), "jpg");
        BufferedImage decoded = PhotoStore.decode(big);
        assertEquals(3000, decoded.getWidth(), "subsampled by 2: never the full 6000 x 4000 raster");
        assertEquals(2000, decoded.getHeight());
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            Photo p = s.store(upload(encode(picture(4000, 3000, BufferedImage.TYPE_3BYTE_BGR), "jpg")));
            assertEquals(2048, p.widthPx);
            assertEquals(1536, p.heightPx);
            BufferedImage back = stored(s, p);
            assertEquals(2048, back.getWidth());
            BufferedImage thumb = ImageIO.read(s.thumbFile(p));
            assertEquals(320, thumb.getWidth());
            assertEquals(240, thumb.getHeight());
        }
    }

    /** A JPEG with an EXIF APP1 segment (a phone's location) right after its SOI. */
    static byte[] withExif(byte[] jpeg) {
        byte[] exif = "Exif\0\0GPS 37.7749 N 122.4194 W".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write((exif.length + 2) >> 8);
        out.write((exif.length + 2) & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static boolean hasApp1(byte[] b) {
        for (int i = 0; i + 1 < b.length; i++) {
            if ((b[i] & 0xFF) == 0xFF && (b[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void theStoredPictureHasNoMetadata() throws Exception {
        byte[] phone = withExif(encode(picture(640, 480, BufferedImage.TYPE_INT_RGB), "jpg"));
        assertTrue(hasApp1(phone));
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            Photo p = s.store(upload(phone));
            byte[] kept = Files.readAllBytes(s.file(p).toPath());
            assertFalse(hasApp1(kept), "no EXIF (APP1) segment");
            assertFalse(new String(kept, StandardCharsets.ISO_8859_1).contains("GPS"));
        }
    }

    @Test
    public void equalPicturesShareAFileRemovalKeepsItRestoreAndTheSweep() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            byte[] bytes = encode(picture(400, 300, BufferedImage.TYPE_INT_RGB), "jpg");
            Photo a = s.store(upload(bytes));
            Photo b = s.store(upload(bytes));
            assertNotEquals(a.id, b.id);
            assertEquals(a.file, b.file, "content-addressed");
            Photo removed = s.remove(a.id, "blurred", "tester");
            assertNotNull(removed.removedAtMs);
            assertEquals("blurred", removed.removedWhy);
            assertEquals(1, db.photos(Tip.SEED_KEY, null, null, null, false, 10).size());
            assertEquals(2, db.photos(Tip.SEED_KEY, null, null, null, true, 10).size());
            assertNull(s.restore(a.id, "tester").removedAtMs);
            // removal: the file stays 7 days, and while another live row shares it
            s.remove(a.id, null, null);
            long eightDays = System.currentTimeMillis() + 8L * 24 * 3600 * 1000;
            assertEquals(0, s.sweep(eightDays), "b shares the file");
            s.remove(b.id, null, null);
            assertEquals(0, s.sweep(System.currentTimeMillis()), "within the 7 days");
            assertTrue(s.file(a).isFile());
            assertEquals(2, s.sweep(eightDays), "the picture and its thumbnail");
            assertFalse(s.file(a).isFile());
            EngineRefusal gone = assertThrows(EngineRefusal.class, () -> s.restore(a.id, null));
            assertEquals(410, gone.status);
            // an orphan file (a crash between the move and the insert) and a result photo never linked for 30 days
            File orphan = new File(new File(s.folder(), "2026-10"), "0123456789abcdef0123.jpg");
            orphan.getParentFile().mkdirs();
            Files.write(orphan.toPath(), bytes);
            PhotoStore.Input res = upload(encode(picture(500, 300, BufferedImage.TYPE_INT_RGB), "jpg"));
            res.kind = Photo.KIND_RESULT;
            res.source = Photo.SOURCE_TOP_CAMERA;
            res.jobId = "job-x";
            Photo result = s.store(res);
            assertEquals(1, s.sweep(System.currentTimeMillis()), "the orphan only");
            assertFalse(orphan.exists());
            assertTrue(s.file(result).isFile());
            assertEquals(2, s.sweep(System.currentTimeMillis() + 31L * 24 * 3600 * 1000), "the unlinked result photo");
            // a result photo is the evidence: never removed
            EngineRefusal ev = assertThrows(EngineRefusal.class, () -> s.remove(result.id, null, null));
            assertEquals("A result photo is the calibration's evidence: it stays.", ev.getMessage());
            // a path that leaves the folder
            Photo bad = new Photo();
            bad.file = "../../etc/passwd";
            IllegalStateException out = assertThrows(IllegalStateException.class, () -> s.file(bad));
            assertEquals("photo file outside its folder", out.getMessage());
        }
    }

    @Test
    public void theMainPictureIsChosenOrTheNewestSetupPicture() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            Photo a = s.store(upload(encode(picture(300, 200, BufferedImage.TYPE_INT_RGB), "jpg")));
            Photo b = s.store(upload(encode(picture(320, 200, BufferedImage.TYPE_INT_RGB), "jpg")));
            assertEquals(b.id, db.mainPhoto(Tip.SEED_KEY).id, "the newest setup picture");
            s.setMain(a.id, "tester");
            assertEquals(a.id, db.mainPhoto(Tip.SEED_KEY).id, "chosen");
            s.remove(a.id, null, null);
            assertEquals(b.id, db.mainPhoto(Tip.SEED_KEY).id, "a removed picture is never the main one");
            assertNull(db.mainPhoto("25g-straight"));
        }
    }

    @Test
    public void theLimits() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            // the caption
            PhotoStore.Input c = upload(encode(picture(100, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            c.caption = "x".repeat(201);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.store(c));
            assertEquals("The caption must be at most 200 characters.", e.getMessage());
            c.caption = "line\u0007one\nline two";
            assertEquals("line one line two", s.store(c).caption);
            // 20 pictures of a setup not linked to a calibration
            for (int i = 1; i < PhotoStore.MAX_UNLINKED_PER_SETUP; i++) {
                s.store(upload(encode(picture(100 + i, 100, BufferedImage.TYPE_INT_RGB), "jpg")));
            }
            e = assertThrows(EngineRefusal.class, () -> s.store(upload(encode(picture(99, 99,
                    BufferedImage.TYPE_INT_RGB), "jpg"))));
            assertEquals("too-many", e.code);
            assertEquals("The " + Tip.SEED_NAME + " has 20 pictures already: remove one first.", e.getMessage());
        }
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            // the disk cap counts the removable pictures only
            Photo big = new Photo();
            big.kind = Photo.KIND_RESULT;
            big.source = Photo.SOURCE_TOP_CAMERA;
            big.setupKey = Tip.SEED_KEY;
            big.file = "2026-10/aaaaaaaaaaaaaaaaaaaa.jpg";
            big.sha256 = "a";
            big.bytes = PhotoStore.DISK_CAP_BYTES;
            db.insertPhoto(big);
            s.store(upload(encode(picture(100, 100, BufferedImage.TYPE_INT_RGB), "jpg")));
            big.kind = Photo.KIND_SETUP;
            big.file = "2026-10/bbbbbbbbbbbbbbbbbbbb.jpg";
            Photo setup = db.insertPhoto(big);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.store(upload(encode(picture(101, 100,
                    BufferedImage.TYPE_INT_RGB), "jpg"))));
            assertEquals("disk", e.code);
            assertEquals("The pictures take 2.0 GB already: remove old ones first.", e.getMessage());
            // removing old ones makes room at once (the file stays for its undo until the start sweep)
            s.remove(setup.id, "too large", "tester");
            Photo more = s.store(upload(encode(picture(102, 100, BufferedImage.TYPE_INT_RGB), "jpg")));
            assertEquals(Photo.KIND_SETUP, more.kind);
        }
    }

    @Test
    public void twoUploadsAtOnceOneIsBusy() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            byte[] bytes = encode(picture(200, 200, BufferedImage.TYPE_INT_RGB), "jpg");
            assertTrue(PhotoStore.PIPELINE.tryAcquire(), "the first upload holds the pipeline");
            try {
                EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.storeExclusive(upload(bytes)));
                assertEquals(409, e.status);
                assertEquals("busy", e.code);
                assertEquals("Another picture is being stored: try again in a moment.", e.getMessage());
            }
            finally {
                PhotoStore.PIPELINE.release();
            }
            assertNotNull(s.storeExclusive(upload(bytes)));
            assertEquals(1, PhotoStore.PIPELINE.availablePermits());
        }
    }

    @Test
    public void linksAreForTheRecordsOfTheSameSetupAndNeedle() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            db.insertTipSetupVersion(TipSetupsTest.straight25());
            PadCalibrationRecord rec = new PadCalibrationRecord();
            rec.jobId = "job-1";
            rec.typeKey = "chip-0603";
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            rec.setupKey = "25g-straight";
            rec.needleSinceMs = 5000L;
            rec = db.insertPadCalibration(rec);
            PhotoStore.Input in = upload(encode(picture(100, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            in.kind = Photo.KIND_TIP;
            in.calibrationIds.add(rec.id);
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.store(in));
            assertEquals("setup-mismatch", e.code);
            assertEquals("This picture is of the " + Tip.SEED_NAME + "; calibration " + rec.id + " was made with the "
                    + "25G straight.", e.getMessage());
            in.setupKey = "25g-straight";
            in.source = Photo.SOURCE_BOTTOM_CAMERA;
            in.needleSinceMs = 6000L; // taken after a needle change
            assertEquals("needle-changed", assertThrows(EngineRefusal.class, () -> s.store(in)).code);
            in.needleSinceMs = 5000L;
            Photo p = s.store(in);
            assertEquals(Arrays.asList(rec.id), p.links);
            assertEquals(Photo.ROLE_TIP, db.photoLinks(p.id, null).get(0).role);
            // six tip pictures per record
            for (int i = 1; i < PhotoStore.MAX_TIP_PER_RECORD; i++) {
                PhotoStore.Input more = upload(encode(picture(100 + i, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
                more.kind = Photo.KIND_TIP;
                more.setupKey = "25g-straight";
                more.calibrationIds.add(rec.id);
                s.store(more);
            }
            PhotoStore.Input seventh = upload(encode(picture(99, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            seventh.kind = Photo.KIND_TIP;
            seventh.setupKey = "25g-straight";
            seventh.calibrationIds.add(rec.id);
            assertEquals("too-many", assertThrows(EngineRefusal.class, () -> s.store(seventh)).code);
        }
    }

    @Test
    public void linksMadeLaterFollowTheRulesOfTheStore() throws Exception {
        // POST /api/photos/{id}/links reaches DispenseService.linkPhotos: the same setup, the same needle and at most
        // six tip pictures per record as an upload with the record
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            f.add25();
            PadCalibrationRecord rec = new PadCalibrationRecord();
            rec.jobId = "job-22g";
            rec.typeKey = "chip-0603";
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            rec.setupKey = Tip.SEED_KEY;
            rec.needleSinceMs = svc.getConfig().needleChangedAtMs;
            rec = svc.getDb().insertPadCalibration(rec);
            java.util.List<Long> ids = new java.util.ArrayList<>();
            for (int i = 0; i <= PhotoStore.MAX_TIP_PER_RECORD; i++) {
                PhotoStore.Input in = upload(encode(picture(100 + i, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
                in.kind = Photo.KIND_TIP;
                ids.add(svc.storePhoto(in).id);
            }
            assertEquals(PhotoStore.MAX_TIP_PER_RECORD, svc.linkPhotos(ids.subList(0, PhotoStore.MAX_TIP_PER_RECORD),
                    Arrays.asList(rec.id), Photo.ROLE_TIP).size());
            long recId = rec.id;
            EngineRefusal many = assertThrows(EngineRefusal.class, () -> svc.linkPhotos(ids.subList(
                    PhotoStore.MAX_TIP_PER_RECORD, ids.size()), Arrays.asList(recId), Photo.ROLE_TIP));
            assertEquals("too-many", many.code);
            // a picture of another setup
            PhotoStore.Input other = upload(encode(picture(90, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            other.setupKey = "25g-straight";
            long otherId = svc.storePhoto(other).id;
            assertEquals("setup-mismatch", assertThrows(EngineRefusal.class, () -> svc.linkPhotos(Arrays.asList(
                    otherId), Arrays.asList(recId), Photo.ROLE_TIP)).code);
            // a bottom-camera picture of the needle after a change
            NeedleChange nc = svc.recordNeedleChange(null, null);
            PhotoStore.Input below = upload(encode(picture(91, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            below.kind = Photo.KIND_TIP;
            below.source = Photo.SOURCE_BOTTOM_CAMERA;
            below.needleSinceMs = nc.atMs;
            long belowId = svc.storePhoto(below).id;
            assertEquals("needle-changed", assertThrows(EngineRefusal.class, () -> svc.linkPhotos(Arrays.asList(
                    belowId), Arrays.asList(recId), Photo.ROLE_TIP)).code);
            assertEquals(PhotoStore.MAX_TIP_PER_RECORD, svc.getDb().photoLinks(null, recId).size(), "nothing more");
        }
    }

    @Test
    public void aRecordWithoutANeedleTokenTakesOnlyPicturesOfItsNeedle() throws Exception {
        // a record made before any needle change was recorded (or before schema 3) has no token: a bottom-camera
        // picture taken after a later change is of another needle (review of §22.4)
        try (DispenseDb db = DispenseDb.inMemory()) {
            PhotoStore s = store(db);
            PadCalibrationRecord rec = new PadCalibrationRecord();
            rec.jobId = "job-old";
            rec.typeKey = "chip-0603";
            rec.outcome = PadCalibrationRecord.PROVISIONAL;
            rec.setupKey = Tip.SEED_KEY;
            rec = db.insertPadCalibration(rec);
            PhotoStore.Input in = upload(encode(picture(100, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            in.kind = Photo.KIND_TIP;
            in.source = Photo.SOURCE_BOTTOM_CAMERA;
            in.calibrationIds.add(rec.id);
            in.needleSinceMs = rec.createdAtMs + 1; // the needle was changed after the record
            EngineRefusal e = assertThrows(EngineRefusal.class, () -> s.store(in));
            assertEquals("needle-changed", e.code);
            in.needleSinceMs = rec.createdAtMs - 1; // the needle the record was made with
            assertEquals(Arrays.asList(rec.id), s.store(in).links);
            PhotoStore.Input never = upload(encode(picture(101, 100, BufferedImage.TYPE_INT_RGB), "jpg"));
            never.kind = Photo.KIND_TIP;
            never.source = Photo.SOURCE_BOTTOM_CAMERA;
            never.calibrationIds.add(rec.id);
            never.needleSinceMs = null; // no change was ever recorded
            assertEquals(Arrays.asList(rec.id), s.store(never).links);
        }
    }
}
