package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.DotImager;
import org.openpnp.instancelabs.dispense.Photo;
import org.openpnp.instancelabs.dispense.PhotoStore;
import org.openpnp.instancelabs.dispense.TipCamera;
import org.openpnp.instancelabs.padprofile.Tip;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Pictures through the API on the SIM machine (PROFILE_CALIBRATION_DESIGN.md §22.4, §22.6, §22.8): an upload stored and
 * served by id with its ETag (304 when unchanged) and its thumbnail; each refusal of the upload (not a picture, too
 * small, larger than 8 MB, a caption too long, while the engine runs, a second one at once); the list's filters;
 * removal (410), restore and the main picture; a result photo that stays; links refused to another setup's record and,
 * for a picture from below, to the record of another needle; the picture route with a fake camera and its refusals.
 */
public class PhotoApiSimTest {
    TipSim t;

    @BeforeEach
    public void before() throws Exception {
        t = new TipSim();
        t.bench();
    }

    @AfterEach
    public void after() throws Exception {
        t.svc.setTipCamera(null);
        t.close();
    }

    /** A picture of w x h pixels (a gradient and a disc) as JPEG or PNG bytes. */
    static byte[] picture(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < h; y += 8) {
            g.setColor(new Color(40 + y * 150 / h, 60, 90));
            g.fillRect(0, y, w, 8);
        }
        g.setColor(new Color(220, 200, 40));
        g.fillOval(w / 4, h / 4, w / 2, h / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    TipSim.Res upload(String query, byte[] body) throws Exception {
        return t.send("POST", "/api/photos?" + query, body, "image/jpeg");
    }

    JsonObject uploaded(String query, byte[] body) throws Exception {
        TipSim.Res r = upload(query, body);
        assertEquals(201, r.status, r.toString());
        return r.json().getAsJsonObject("photo");
    }

    @Test
    public void anUploadIsStoredServedRemovedAndRestored() throws Exception {
        JsonObject p = uploaded("kind=setup&setup=ad22tta-b&caption=" + TipSim.SEED + "%20from%20the%20side",
                picture(800, 600, "jpg"));
        assertEquals("setup", TipSim.str(p, "kind"));
        assertEquals("upload", TipSim.str(p, "source"));
        assertEquals(TipSim.SEED, TipSim.str(p, "setupKey"));
        assertEquals(800, p.get("widthPx").getAsInt());
        assertTrue(p.get("removable").getAsBoolean());
        long id = p.get("id").getAsLong();
        assertEquals("/api/photos/" + id + ".jpg", TipSim.str(p, "url"));

        TipSim.Res file = t.get(TipSim.str(p, "url"));
        assertEquals(200, file.status, file.toString());
        assertEquals("image/jpeg", file.header("Content-Type"));
        assertEquals("private, no-cache", file.header("Cache-Control"));
        String etag = file.header("ETag");
        assertNotNull(etag);
        assertEquals(0xff, file.raw.body()[0] & 0xff, "a JPEG");
        TipSim.Res same = t.get(TipSim.str(p, "url"), "If-None-Match", etag);
        assertEquals(304, same.status, "unchanged: " + same);
        assertEquals(0, same.raw.body().length);
        TipSim.Res thumb = t.get(TipSim.str(p, "thumbUrl"));
        assertEquals(200, thumb.status);
        BufferedImage small = ImageIO.read(new ByteArrayInputStream(thumb.raw.body()));
        assertTrue(Math.max(small.getWidth(), small.getHeight()) <= PhotoStore.THUMB_PX, small.getWidth() + "");

        // the list and its filters
        assertEquals(1, TipSim.okArray(t.get("/api/photos?setup=ad22tta-b")).size());
        assertEquals(0, TipSim.okArray(t.get("/api/photos?kind=tip")).size());
        assertEquals(1, TipSim.okArray(t.get("/api/photos?kind=setup&limit=5")).size());
        // the setup's main picture: the newest setup picture, or the one chosen
        JsonObject second = uploaded("kind=tip&setup=ad22tta-b", picture(400, 300, "png"));
        assertEquals(id, mainPhotoId());
        TipSim.ok(t.post("/api/photos/" + second.get("id").getAsLong() + "/main", "{}"));
        assertEquals(second.get("id").getAsLong(), mainPhotoId());

        // removal: gone at once (410), listed only when asked, restored within 7 days
        JsonObject rm = TipSim.ok(t.call("DELETE", "/api/photos/" + id, "{\"why\":\"blurred\"}"));
        assertTrue(rm.get("removed").getAsBoolean());
        assertTrue(rm.get("restoreUntilMs").getAsLong() > System.currentTimeMillis() + 6 * 24 * 3600 * 1000L);
        JsonObject gone = TipSim.refused(t.get("/api/photos/" + id + ".jpg"), 410, "removed");
        assertTrue(TipSim.str(gone, "reason").contains("(blurred)"), gone.toString());
        assertEquals(1, TipSim.okArray(t.get("/api/photos?setup=ad22tta-b")).size());
        JsonArray all = TipSim.okArray(t.get("/api/photos?setup=ad22tta-b&removed=true"));
        assertEquals(2, all.size());
        TipSim.ok(t.post("/api/photos/" + id + "/restore", "{}"));
        assertEquals(200, t.get("/api/photos/" + id + ".jpg").status);
        TipSim.refused(t.get("/api/photos/99999.jpg"), 404, "not-found");

        // a result photo is the calibration's evidence: it stays
        Photo result = storeResult();
        JsonObject stays = TipSim.refused(t.call("DELETE", "/api/photos/" + result.id, "{}"), 409, "result-photo");
        assertEquals("A result photo is the calibration's evidence: it stays.", TipSim.str(stays, "reason"));
    }

    long mainPhotoId() throws Exception {
        JsonObject seed = TipSim.ok(t.get("/api/tip-setups")).getAsJsonArray("setups").get(0).getAsJsonObject();
        return seed.getAsJsonObject("mainPhoto").get("id").getAsLong();
    }

    /** A result photo as a pad calibration step stores it (the engine side). */
    Photo storeResult() throws Exception {
        PhotoStore.Input in = new PhotoStore.Input();
        in.bytes = picture(640, 480, "jpg");
        in.kind = Photo.KIND_RESULT;
        in.source = Photo.SOURCE_TOP_CAMERA;
        in.setupKey = TipSim.SEED;
        in.jobId = "pc-1";
        in.doseSteps = 800;
        return t.svc.storePhoto(in);
    }

    @Test
    public void uploadsAreCheckedBeforeTheyAreStored() throws Exception {
        String q = "kind=tip&setup=ad22tta-b";
        JsonObject notImage = TipSim.refused(upload(q, "hello, not a picture".getBytes("UTF-8")), 400, "not-an-image");
        assertEquals("This file is not a JPEG or PNG picture.", TipSim.str(notImage, "reason"));
        TipSim.refused(upload(q, picture(100, 100, "gif")), 400, "not-an-image");
        JsonObject small = TipSim.refused(upload(q, picture(32, 32, "png")), 400, "size");
        assertEquals("The picture is 32 × 32 px: at least 64 and at most 12,000 px a side.", TipSim.str(small,
                "reason"));
        // over 8 MiB, declared: refused before a byte is read
        String big = t.rawAnswer("/api/photos?" + q, PhotoStore.MAX_REQUEST_BYTES + 1L);
        assertTrue(big.startsWith("HTTP/1.1 413"), big);
        assertTrue(big.contains(PhotoEndpoints.TOO_LARGE), big);
        StringBuilder caption = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            caption.append('x');
        }
        JsonObject longCaption = TipSim.refused(upload(q + "&caption=" + caption, picture(100, 100, "jpg")), 400,
                "caption");
        assertEquals("The caption must be at most 200 characters.", TipSim.str(longCaption, "reason"));
        TipSim.refused(upload("kind=result&setup=ad22tta-b", picture(100, 100, "jpg")), 400, "bad-request");
        TipSim.refused(upload("kind=tip&setup=nope", picture(100, 100, "jpg")), 404, "not-found");

        // one picture at a time: a second one while another is being stored is refused at once
        assertTrue(PhotoStore.PIPELINE.tryAcquire());
        try {
            JsonObject busy = TipSim.refused(upload(q, picture(100, 100, "jpg")), 409, "busy");
            assertEquals("Another picture is being stored: try again in a moment.", TipSim.str(busy, "reason"));
        }
        finally {
            PhotoStore.PIPELINE.release();
        }
        // no decode while the engine runs a task (a mount holds the engine)
        t.add25();
        CountDownLatch in = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        TipSim.hook(point -> {
            if ("switch".equals(point)) {
                in.countDown();
                try {
                    go.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        CompletableFuture<TipSim.Res> mount = CompletableFuture.supplyAsync(() -> {
            try {
                return t.post("/api/tip-setups/" + TipSim.K25 + "/mount", "{}");
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        try {
            assertTrue(in.await(30, TimeUnit.SECONDS));
            TipSim.Res during = upload(q, picture(100, 100, "jpg"));
            assertEquals(409, during.status, during.toString());
        }
        finally {
            go.countDown();
        }
        assertEquals(200, mount.get(30, TimeUnit.SECONDS).status);
        assertEquals(0, TipSim.okArray(t.get("/api/photos?removed=true")).size(), "nothing stored");
    }

    @Test
    public void linksFollowTheSetupAndTheNeedleOfTheRecord() throws Exception {
        // a record of the 22G: chip-0603 raised by hand
        TipSim.ok(t.call("PUT", "/api/pad-types/chip-0603", "{\"forSetup\":\"ad22tta-b\",\"status\":\"characterized\"}"));
        long rec = TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0603")).get(0).getAsJsonObject().get("id")
                .getAsLong();
        JsonObject mine = uploaded("kind=tip&setup=ad22tta-b&calibrations=" + rec, picture(200, 150, "jpg"));
        assertEquals("[" + rec + "]", mine.getAsJsonArray("links").toString());
        JsonObject listed = TipSim.okArray(t.get("/api/pad-calibrations?typeKey=chip-0603")).get(0).getAsJsonObject();
        assertEquals(1, listed.getAsJsonArray("photos").size(), listed.toString());
        // a picture of another setup is never shown with this record
        t.add25();
        JsonObject theirs = uploaded("kind=tip&setup=25g-straight", picture(200, 150, "jpg"));
        JsonObject mismatch = TipSim.refused(t.post("/api/photos/" + theirs.get("id").getAsLong() + "/links",
                "{\"calibrationIds\":[" + rec + "]}"), 409, "setup-mismatch");
        assertEquals("This picture is of the 25G straight; calibration " + rec + " was made with the " + Tip.SEED_NAME
                + ".", TipSim.str(mismatch, "reason"));
        TipSim.refused(upload("kind=tip&setup=25g-straight&calibrations=" + rec, picture(200, 150, "jpg")), 409,
                "setup-mismatch");

        // a picture from below after a needle change belongs to the needle put on, not to the record before it
        Camera cam = new Camera();
        t.svc.setTipCamera(cam);
        TipSim.ok(t.post("/api/needle/changed", "{\"why\":\"a new 22G\"}"));
        JsonObject pic = TipSim.ok(t.post("/api/tip-setups/ad22tta-b/picture", "{\"wait\":true}"));
        JsonObject photo = pic.getAsJsonObject("result").getAsJsonObject("photo");
        assertEquals("bottom-camera", TipSim.str(photo, "source"));
        assertEquals(t.token(), TipSim.lng(photo, "needleSinceMs"));
        JsonObject needle = TipSim.refused(t.post("/api/photos/" + photo.get("id").getAsLong() + "/links",
                "{\"calibrationIds\":[" + rec + "]}"), 409, "needle-changed");
        assertTrue(TipSim.str(needle, "reason").startsWith("This picture was taken with another needle than calibration "
                + rec), needle.toString());
    }

    @Test
    public void theMountedTipIsPhotographedFromBelow() throws Exception {
        Camera cam = new Camera();
        t.svc.setTipCamera(cam);
        JsonObject r = TipSim.ok(t.post("/api/tip-setups/ad22tta-b/picture", "{\"wait\":true}"));
        JsonObject res = r.getAsJsonObject("result");
        assertTrue(res.get("ok").getAsBoolean(), r.toString());
        JsonObject photo = res.getAsJsonObject("photo");
        assertEquals("tip", TipSim.str(photo, "kind"));
        assertTrue(TipSim.str(photo, "caption").matches("From below at \\d\\d:\\d\\d, at travel height \\(may be soft\\): "
                + "the " + Tip.SEED_NAME), photo.toString());
        assertEquals(1, cam.captures);
        assertEquals(200, t.get(TipSim.str(photo, "url")).status);

        // only the mounted tip; no camera looking up; never without a home
        t.add25();
        JsonObject other = TipSim.refused(t.post("/api/tip-setups/25g-straight/picture", "{}"), 409, "refused");
        assertEquals("Only the mounted tip can be photographed: mount the 25G straight first.", TipSim.str(other,
                "reason"));
        cam.available = false;
        JsonObject none = TipSim.refused(t.post("/api/tip-setups/ad22tta-b/picture", "{}"), 409, "refused");
        assertEquals("No bottom camera: OpenPnP has no camera looking up.", TipSim.str(none, "reason"));
        cam.available = true;
        t.sim.machine.setHomed(false);
        JsonObject unhomed = TipSim.ok(t.post("/api/tip-setups/ad22tta-b/picture", "{\"wait\":true}"));
        assertFalse(unhomed.getAsJsonObject("result").get("ok").getAsBoolean(), unhomed.toString());
        assertTrue(unhomed.toString().contains("Home first: the head moves to the bottom camera."), unhomed.toString());
        assertEquals(1, cam.captures, "nothing moved");
    }

    /** A camera looking up at (217.13, 152.32) that draws a ring. */
    static final class Camera implements TipCamera {
        volatile boolean available = true;
        volatile int captures;

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public double[] locationMm() {
            return new double[] {217.13, 152.32, 20};
        }

        @Override
        public DotImager.Frame capture() {
            captures++;
            BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(new Color(25, 25, 25));
            g.fillRect(0, 0, 320, 240);
            g.setColor(new Color(200, 200, 200));
            g.fillOval(130, 90, 60, 60);
            g.setColor(Color.BLACK);
            g.fillOval(150, 110, 20, 20);
            g.dispose();
            return new DotImager.Frame(img, 0.02, 0.02, 217.13, 152.32);
        }
    }
}
