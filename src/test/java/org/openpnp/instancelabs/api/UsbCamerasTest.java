package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openpnp.instancelabs.dispense.DispenseConfig;

/**
 * BR-102 on fake frame sources (docs/backend-requests.md "BR-102"): one capture per camera, shared by every request,
 * opened on the first in the camera's mode and released when idle; the first frames after an open dropped; after=;
 * busy, missing and a device that vanishes mid-stream reported, the next request trying again; one encode per frame
 * and width. And the device rules: off the simulator only /dev/v4l/by-id/ paths; file: only simulated, in the
 * fixtures dir, JPEG bytes.
 */
public class UsbCamerasTest {
    static final long IDLE_MS = 1000;
    static final String BY_ID = "/dev/v4l/by-id/usb-Image+_Galyimage_Live_Camera_HU20260807-video-index0";

    @TempDir
    Path dir;
    final List<UsbCameras.Capture> captures = new ArrayList<>();
    Process holder;

    @AfterEach
    public void after() {
        for (UsbCameras.Capture c : captures) {
            c.stop();
        }
        if (holder != null) {
            holder.destroyForcibly();
        }
    }

    /** Numbered frames (frame n carries n in its first pixel); what each open asked for; opens, closes, reads. */
    static final class FakeCamera implements UsbCameras.Opener {
        final AtomicInteger opens = new AtomicInteger(), closes = new AtomicInteger(), reads = new AtomicInteger();
        volatile DispenseConfig.UsbCamera asked;
        volatile IOException openFails;
        volatile long openMs;
        /** The next read fails as an unplugged camera's. */
        volatile boolean unplugged;

        @Override
        public UsbCameras.FrameSource open(DispenseConfig.UsbCamera cam) throws IOException {
            asked = cam;
            opens.incrementAndGet();
            sleep(openMs);
            if (openFails != null) {
                throw openFails;
            }
            return new UsbCameras.FrameSource() {
                @Override
                public BufferedImage read() throws IOException {
                    if (unplugged) {
                        throw new IOException("VIDIOC_DQBUF: No such device");
                    }
                    return numbered(reads.incrementAndGet());
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            };
        }
    }

    static BufferedImage numbered(int n) {
        BufferedImage b = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        b.setRGB(0, 0, n);
        return b;
    }

    static int number(BufferedImage b) {
        return b.getRGB(0, 0) & 0xffffff;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void waitFor(BooleanSupplier ok, long ms, String what) throws InterruptedException {
        long t0 = System.currentTimeMillis();
        while (!ok.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() - t0 < ms, what);
            Thread.sleep(20);
        }
    }

    static DispenseConfig.UsbCamera cam(String device, String fourcc, int w, int h) {
        DispenseConfig.UsbCamera c = new DispenseConfig.UsbCamera();
        c.device = device;
        c.fourcc = fourcc;
        c.width = w;
        c.height = h;
        return c;
    }

    UsbCameras.Capture capture(FakeCamera fake, Path device, int w, int h) {
        UsbCameras.Capture c = new UsbCameras.Capture("gauge", cam(device.toString(), "MJPG", w, h), fake, null);
        captures.add(c);
        return c;
    }

    Path device() throws IOException {
        return Files.createFile(dir.resolve("video-index0"));
    }

    /** A frame captured after {@code after}, waiting at most {@code waitMs}. */
    static UsbCameras.Frame frame(UsbCameras.Capture c, long after, long waitMs) throws Exception {
        return c.await(after, System.currentTimeMillis() + waitMs, IDLE_MS);
    }

    static Object why(ApiException e) {
        return e.details.get("why");
    }

    // ------------------------------------------------------------------------------------------
    // The capture
    // ------------------------------------------------------------------------------------------

    @Test
    public void opensOnTheFirstRequestInTheCamerasModeAndDropsTheFirstFrames() throws Exception {
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, device(), 1280, 720);
        Thread.sleep(100);
        assertEquals(0, fake.opens.get(), "nothing opens before a request");
        long t0 = System.currentTimeMillis();
        UsbCameras.Frame f = frame(c, t0, 3000);
        assertEquals(1, fake.opens.get());
        assertEquals(c.cam.device, fake.asked.device);
        assertEquals("MJPG 1280x720", fake.asked.mode(), "the gauge's mode: MJPEG, never YUYV on the shared hub");
        // a 1-buffer grab after an open was corrupt (Process, 11-04): the first frames are never served
        assertEquals(UsbCameras.DROP_AFTER_OPEN + 1, number(f.img));
        assertTrue(f.at > t0, f.at + " > " + t0);
    }

    @Test
    public void oneCaptureServesEveryRequestAndAfterWaitsForANewerFrame() throws Exception {
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, device(), 1920, 1080);
        UsbCameras.Frame a = frame(c, System.currentTimeMillis(), 3000);
        int reads = fake.reads.get();
        // another client asking for a frame since before a was taken gets a: nothing more is read
        assertSame(a, frame(c, a.at - 1, 3000));
        assertEquals(reads, fake.reads.get());
        // after= the last frame's time: the next frame
        UsbCameras.Frame b = frame(c, a.at, 3000);
        assertTrue(b.at > a.at && number(b.img) > number(a.img), b.at + " " + a.at);
        // a time ahead: read once it has passed (the gauge read wants frames >= 10 s after the pump starts)
        long ahead = System.currentTimeMillis() + 300;
        UsbCameras.Frame d = frame(c, ahead, 3000);
        assertTrue(d.at > ahead, d.at + " > " + ahead);
        assertEquals(1, fake.opens.get(), "one capture for every request");
    }

    @Test
    public void noNewerFrameWithinTheWaitIs504AndAHangingDeviceCostsOnlyTheWait() throws Exception {
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, device(), 1280, 720);
        long t = System.currentTimeMillis();
        ApiException e = assertThrows(ApiException.class, () -> frame(c, t + 2000, 300));
        assertEquals(504, e.status, e.getMessage());
        long took = System.currentTimeMillis() - t;
        assertTrue(took >= 250 && took < 1500, took + " ms");
        // a device that takes seconds to open: the request ends at its wait, the engine never waits on it
        FakeCamera slow = new FakeCamera();
        slow.openMs = 3000;
        Path other = Files.createFile(dir.resolve("slow-video-index0"));
        UsbCameras.Capture s = capture(slow, other, 1280, 720);
        long t1 = System.currentTimeMillis();
        e = assertThrows(ApiException.class, () -> frame(s, t1, 300));
        assertEquals(504, e.status, e.getMessage());
        assertTrue(System.currentTimeMillis() - t1 < 1500, "answered at its wait");
    }

    @Test
    public void releasedAfterIdleAndOpenedAgainOnTheNextRequest() throws Exception {
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, device(), 1280, 720);
        frame(c, System.currentTimeMillis(), 3000);
        Thread.sleep(IDLE_MS / 4);
        frame(c, System.currentTimeMillis(), 3000);
        assertEquals(0, fake.closes.get(), "a request within the idle time keeps the device");
        waitFor(() -> fake.closes.get() == 1, IDLE_MS + 3000, "released after the idle time");
        int atRelease = fake.reads.get();
        Thread.sleep(200);
        assertEquals(atRelease, fake.reads.get(), "nothing is read while released");
        UsbCameras.Frame again = frame(c, System.currentTimeMillis(), 3000);
        assertEquals(2, fake.opens.get(), "opened again");
        assertEquals(atRelease + UsbCameras.DROP_AFTER_OPEN + 1, number(again.img), "the first frames dropped again");
    }

    @Test
    public void busyIsReportedNamingTheHolderAndTheNextRequestTriesAgain() throws Exception {
        Path dev = device();
        FakeCamera fake = new FakeCamera();
        fake.openFails = new IOException("VIDIOC_S_FMT: Device or resource busy");
        UsbCameras.Capture c = capture(fake, dev, 1280, 720);
        ApiException e = assertThrows(ApiException.class, () -> frame(c, System.currentTimeMillis(), 3000));
        assertEquals(503, e.status);
        assertEquals("camera-unavailable", e.code);
        assertEquals("busy: another program has it open", why(e));
        assertEquals("busy: another program has it open", c.lastWhy(), "listed as busy");
        // a program holding the device is named, from /proc/<pid>/fd
        holder = new ProcessBuilder("sh", "-c", "exec 3<\"$0\"; exec sleep 30", dev.toString()).start();
        waitFor(() -> String.valueOf(UsbCameras.holderOf(dev)).startsWith("sleep"), 5000, "the holder in /proc");
        e = assertThrows(ApiException.class, () -> frame(c, System.currentTimeMillis(), 3000));
        assertEquals("busy: sleep (pid " + holder.pid() + ") has it open", why(e));
        assertEquals(2, fake.opens.get(), "the next request tried again");
        holder.destroyForcibly().waitFor();
        fake.openFails = null;
        assertNotNull(frame(c, System.currentTimeMillis(), 3000));
        assertEquals(3, fake.opens.get());
        assertNull(c.lastWhy(), "a frame clears it");
    }

    @Test
    public void aMissingDeviceIsNotPluggedIn() throws Exception {
        FakeCamera fake = new FakeCamera();
        fake.openFails = new IOException("can't open camera by index");
        Path gone = dir.resolve("usb-Image+_Galyimage_Live_camera_HU123456798765432-video-index0");
        UsbCameras.Capture c = capture(fake, gone, 1920, 1080);
        ApiException e = assertThrows(ApiException.class, () -> frame(c, System.currentTimeMillis(), 3000));
        assertEquals(503, e.status);
        assertEquals("not plugged in: " + gone + " is gone", why(e));
        assertNull(c.lastWhy(), "gone is read from the path each time, not kept");
    }

    @Test
    public void aDeviceThatVanishesMidStreamIsClosedReportedAndOpenedAgain() throws Exception {
        Path dev = device();
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, dev, 1920, 1080);
        UsbCameras.Frame a = frame(c, System.currentTimeMillis(), 3000);
        fake.unplugged = true;
        Files.delete(dev); // udev removes the by-id link
        ApiException e = assertThrows(ApiException.class, () -> frame(c, a.at, 3000));
        assertEquals(503, e.status);
        assertEquals("not plugged in: " + dev + " is gone", why(e));
        waitFor(() -> fake.closes.get() == 1, 3000, "closed");
        // plugged in again: the next request opens it
        Files.createFile(dev);
        fake.unplugged = false;
        UsbCameras.Frame b = frame(c, System.currentTimeMillis(), 3000);
        assertEquals(2, fake.opens.get());
        // no frames while the link stays: stopped
        fake.unplugged = true;
        e = assertThrows(ApiException.class, () -> frame(c, b.at, 3000));
        assertTrue(String.valueOf(why(e)).startsWith("the camera stopped giving pictures"), String.valueOf(why(e)));
        assertEquals(why(e), c.lastWhy());
        waitFor(() -> fake.closes.get() == 2, 3000, "closed");
    }

    @Test
    public void eachNewFrameIsEncodedOncePerWidth() throws Exception {
        FakeCamera fake = new FakeCamera();
        UsbCameras.Capture c = capture(fake, device(), 1920, 1080);
        AtomicInteger encodes = new AtomicInteger();
        UsbCameras.Encoder enc = (img, w) -> {
            encodes.incrementAndGet();
            return ApiServer.Response.bytes(200, "image/test", (number(img) + "@" + w).getBytes(StandardCharsets.UTF_8));
        };
        UsbCameras.Frame a = frame(c, System.currentTimeMillis(), 3000);
        int n = number(a.img);
        assertEquals(n + "@32", body(a.encoded(32, enc)));
        assertEquals(n + "@32", body(a.encoded(32, enc)));
        assertEquals(1, encodes.get(), "two requests at one width: one encode");
        assertEquals(n + "@16", body(a.encoded(16, enc)));
        assertEquals(2, encodes.get());
        // 0 and any width from the frame's own up: the whole frame, one encode
        assertEquals(n + "@0", body(a.encoded(0, enc)));
        assertEquals(n + "@0", body(a.encoded(64, enc)));
        assertEquals(n + "@0", body(a.encoded(4096, enc)));
        assertEquals(3, encodes.get());
        UsbCameras.Frame b = frame(c, a.at, 3000);
        assertEquals(number(b.img) + "@32", body(b.encoded(32, enc)));
        assertEquals(4, encodes.get(), "a new frame is encoded again");
    }

    static String body(ApiServer.Response r) {
        return new String(r.body, StandardCharsets.UTF_8);
    }

    /**
     * BR-102 "Cost": the UI's 2×2 view polls 4 cameras, one request at a time each, 2/s, w=640, after= the last frame.
     * Fake sources give frames of the real sizes (three 1920x1080, the gauge 1280x720) as JPEGs that OpenCV decodes
     * per read, as the V4L2 capture decodes the MJPEG stream (and, for comparison, as frames ready-decoded), then the
     * engine's own scale and encode. Prints the process CPU over 15 s after a 5 s warm-up; run alone with
     * {@code -Dil.measure=true}.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "il.measure", matches = "true")
    public void measureTheCpuOfFourCamerasAtTwoFramesASecond() throws Exception {
        MachineEndpoints m = new MachineEndpoints(null);
        UsbCameras.Encoder enc = (img, w) -> m.encodeFrame(MachineEndpoints.scaled(img, w));
        BufferedImage scene;
        try (InputStream in = UsbCamerasTest.class.getResourceAsStream("/usbcameras/machine.jpg")) {
            scene = javax.imageio.ImageIO.read(in);
        }
        StringBuilder report = new StringBuilder();
        for (boolean decodeEach : new boolean[] {true, false}) {
            List<UsbCameras.Capture> caps = new ArrayList<>();
            for (int[] s : new int[][] {{1920, 1080}, {1920, 1080}, {1920, 1080}, {1280, 720}}) {
                BufferedImage frame = noisy(scene, s[0], s[1]);
                byte[] jpg = org.openpnp.instancelabs.dispense.JpegEncoder.encode(frame, 0.85f);
                UsbCameras.Opener open = cam -> new UsbCameras.FrameSource() {
                    @Override
                    public BufferedImage read() {
                        return decodeEach ? UsbCameras.decode(jpg) : frame;
                    }

                    @Override
                    public void close() {
                    }
                };
                UsbCameras.Capture c = new UsbCameras.Capture("cam" + caps.size(), cam(BY_ID, "MJPG", s[0], s[1]), open,
                        null);
                captures.add(c);
                caps.add(c);
            }
            java.lang.management.OperatingSystemMXBean os = java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            com.sun.management.OperatingSystemMXBean cpu = (com.sun.management.OperatingSystemMXBean) os;
            AtomicInteger frames = new AtomicInteger(), errors = new AtomicInteger();
            java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
            List<Thread> clients = new ArrayList<>();
            for (UsbCameras.Capture c : caps) {
                Thread t = new Thread(() -> {
                    long last = System.currentTimeMillis();
                    while (!stop.get()) {
                        long t0 = System.currentTimeMillis();
                        try {
                            UsbCameras.Frame f = c.await(last, t0 + 5000, 30_000);
                            f.encoded(640, enc);
                            last = f.at;
                            frames.incrementAndGet();
                        }
                        catch (Exception e) {
                            errors.incrementAndGet();
                        }
                        sleep(Math.max(0, 500 - (System.currentTimeMillis() - t0)));
                    }
                });
                t.start();
                clients.add(t);
            }
            Thread.sleep(5000);
            long cpu0 = cpu.getProcessCpuTime(), wall0 = System.nanoTime();
            int n0 = frames.get();
            Thread.sleep(15_000);
            long cpu1 = cpu.getProcessCpuTime(), wall1 = System.nanoTime();
            int n = frames.get() - n0;
            stop.set(true);
            for (Thread t : clients) {
                t.join(10_000);
            }
            for (UsbCameras.Capture c : caps) {
                c.stop();
            }
            report.append(String.format(java.util.Locale.ROOT,
                    "BR-102 CPU, 4 cameras x 2/s at w=640 (%s): %.1f %% of one core; %d frames in %.1f s = %.2f/s, "
                            + "%.1f ms CPU each; %d errors; %d cores%n",
                    decodeEach ? "each frame decoded from JPEG, as from the camera" : "frames ready-decoded",
                    100.0 * (cpu1 - cpu0) / (wall1 - wall0), n, (wall1 - wall0) / 1e9, n / ((wall1 - wall0) / 1e9),
                    (cpu1 - cpu0) / 1e6 / Math.max(1, n), errors.get(), os.getAvailableProcessors()));
        }
        System.out.print(report);
        Files.write(Path.of(System.getProperty("java.io.tmpdir"), "br102-cpu.txt"),
                report.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The scene at w x h with the sensor noise of a camera frame (a noiseless picture compresses unrealistically). */
    static BufferedImage noisy(BufferedImage scene, int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = b.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(scene, 0, 0, w, h, null);
        g.dispose();
        java.util.Random r = new java.util.Random(102);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = b.getRGB(x, y), d = r.nextInt(9) - 4;
                int red = Math.max(0, Math.min(255, (rgb >> 16 & 0xff) + d));
                int grn = Math.max(0, Math.min(255, (rgb >> 8 & 0xff) + d));
                int blu = Math.max(0, Math.min(255, (rgb & 0xff) + d));
                b.setRGB(x, y, red << 16 | grn << 8 | blu);
            }
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------
    // The device rules (Infra 11-05 01:4x)
    // ------------------------------------------------------------------------------------------

    @Test
    public void offTheSimulatorOnlyByIdDevicesInMjpg() throws Exception {
        File fixtures = UsbCameras.fixturesDir(dir.toFile());
        assertNull(UsbCameras.refusal(cam(BY_ID, "MJPG", 1280, 720), false, fixtures));
        assertNull(UsbCameras.refusal(cam(BY_ID, "MJPG", 1920, 1080), true, fixtures), "and in a simulated session");
        for (String d : new String[] {"/dev/video0", "/dev/v4l/by-path/pci-0000:00:14.0-usb-0:3.1:1.0-video-index0",
                "/dev/v4l/by-id/../../video0", "/dev/v4l/by-id/..", "/dev/v4l/by-id/", "/dev/v4l/by-id/a/b",
                "/dev/v4l/by-id/usb-A\u0000B", "/dev/v4l/by-id/usb-A B",
                "/etc/passwd", " " + BY_ID, "", null}) {
            String why = UsbCameras.refusal(cam(d, "MJPG", 1280, 720), false, fixtures);
            assertNotNull(why, d);
            assertTrue(why.contains("/dev/v4l/by-id/"), why);
        }
        // never YUYV: at these sizes it overloads hub 3-1, which top and bottom share
        assertTrue(UsbCameras.refusal(cam(BY_ID, "YUYV", 1280, 720), false, fixtures).contains("MJPG"));
        assertTrue(UsbCameras.refusal(cam(BY_ID, "MJPG", 0, 720), false, fixtures).contains("width"));
        assertTrue(UsbCameras.refusal(cam(BY_ID, "MJPG", 1280, 5000), false, fixtures).contains("height"));
    }

    /** The fixtures dir of a config dir under the test's dir, with the gauge still, a text file and a JPEG outside. */
    File fixtures() throws IOException {
        File config = dir.resolve("config").toFile();
        File fixtures = UsbCameras.fixturesDir(config);
        assertEquals(new File(config, "instancelabs/sim-cameras"), fixtures);
        Path fx = Files.createDirectories(fixtures.toPath());
        try (InputStream in = UsbCamerasTest.class.getResourceAsStream("/usbcameras/gauge.jpg")) {
            Files.copy(in, fx.resolve("gauge.jpg"));
        }
        Files.write(fx.resolve("notes.txt"), "not a picture".getBytes(StandardCharsets.UTF_8));
        Path secret = Files.copy(fx.resolve("gauge.jpg"), config.toPath().resolve("secret.jpg"));
        Files.createSymbolicLink(fx.resolve("link.jpg"), secret);
        return fixtures;
    }

    @Test
    public void fileDevicesOnlyInASimulatedSession() throws Exception {
        File fixtures = fixtures();
        assertNull(UsbCameras.refusal(cam("file:gauge.jpg", "MJPG", 256, 144), true, fixtures));
        String real = UsbCameras.refusal(cam("file:gauge.jpg", "MJPG", 256, 144), false, fixtures);
        assertTrue(real != null && real.contains("simulated"), real);
        // the loader: the still opens only under the same rules
        IOException refused = assertThrows(IOException.class,
                () -> UsbCameras.openStill(cam("file:gauge.jpg", "MJPG", 256, 144), false, fixtures));
        assertTrue(refused.getMessage().contains("simulated"), refused.getMessage());
    }

    @Test
    public void fileDevicesOnlyInTheFixturesDir() throws Exception {
        File fixtures = fixtures();
        assertNull(UsbCameras.refusal(cam("file:" + fixtures + "/gauge.jpg", "MJPG", 256, 144), true, fixtures),
                "a whole path in the fixtures dir");
        for (String d : new String[] {"file:../../secret.jpg", "file:" + dir.resolve("config/secret.jpg"),
                "file:link.jpg", "file:/etc/hostname", "file:"}) {
            String why = UsbCameras.refusal(cam(d, "MJPG", 256, 144), true, fixtures);
            assertTrue(why != null && why.contains(fixtures.toString()), d + ": " + why);
        }
        String none = UsbCameras.refusal(cam("file:nothing.jpg", "MJPG", 256, 144), true, fixtures);
        assertTrue(none != null && none.contains("no such file"), none);
        assertThrows(IOException.class,
                () -> UsbCameras.openStill(cam("file:link.jpg", "MJPG", 256, 144), true, fixtures));
    }

    @Test
    public void fileDevicesOnlyJpegBytes() throws Exception {
        File fixtures = fixtures();
        String text = UsbCameras.refusal(cam("file:notes.txt", "MJPG", 256, 144), true, fixtures);
        assertTrue(text != null && text.contains("FF D8"), text);
        assertThrows(IOException.class,
                () -> UsbCameras.openStill(cam("file:notes.txt", "MJPG", 256, 144), true, fixtures));
        UsbCameras.FrameSource still = UsbCameras.openStill(cam("file:gauge.jpg", "MJPG", 256, 144), true, fixtures);
        BufferedImage img = still.read();
        assertEquals(256, img.getWidth());
        assertEquals(144, img.getHeight());
        still.close();
    }
}
