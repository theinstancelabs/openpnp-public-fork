package org.openpnp.instancelabs.api;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;
import org.openpnp.instancelabs.dispense.DispenseConfig;
import org.openpnp.util.OpenCvUtils;
import org.pmw.tinylog.Logger;

/**
 * BR-102 (docs/backend-requests.md "BR-102"): the two USB cameras, "machine" (the front view of the whole machine) and
 * "gauge" (the regulator close-up), served beside OpenPnP's top and bottom for the UI's 2×2 view. The engine opens
 * them itself, not through OpenPnP's machine.xml, so OpenPnP's vision never sees them.
 *
 * <p>One capture per camera, shared by every client: opened at the first frame request in the settings' mode, its
 * first {@value #DROP_AFTER_OPEN} frames dropped, released {@code usbCameras.idleReleaseMs} after the last request (so
 * Process's bench tools and the laptop previews can open the device when nobody views it). A frame is read only while
 * a request waits for one newer than the latest, so decoding follows the requests, not the camera's rate, and each
 * frame is encoded once per requested width. Device I/O runs on the camera's own thread: a request waits at most
 * {@code usbCameras.frameWaitMs}, and nothing of the engine waits on a camera. Busy, missing and a device that vanishes
 * are reported ({@code why}); the next request tries again.</p>
 *
 * <p>Safety (Infra 11-05 01:4x), checked at the settings write (400) and at every open: off the simulator only
 * {@code /dev/v4l/by-id/…} devices; {@code file:} (a still JPEG, for SIM and CI) only while the controller is the
 * simulator, only under {@link #fixturesDir} and only JPEG bytes, so no settings write can make the camera route
 * serve another file.</p>
 */
final class UsbCameras {
    static final List<String> IDS = Arrays.asList("machine", "gauge");
    private static final Map<String, String> NAMES = Map.of("machine", "Machine", "gauge", "Gauge");
    /** Frames dropped after each open: a 1-buffer grab right after an open was corrupt (Process, the gauge, 11-04). */
    static final int DROP_AFTER_OPEN = 2;
    /** Encodings kept per frame (the UI's tile width and a whole frame, typically). */
    static final int WIDTHS_KEPT = 4;
    static final long MAX_STILL_BYTES = 20L << 20;
    /** One name in /dev/v4l/by-id: printable ASCII (udev escapes the rest), no slash, not "." or "..". */
    private static final Pattern BY_ID = Pattern.compile("/dev/v4l/by-id/(?!\\.\\.?$)[\\x21-\\x7e&&[^/]]+");
    private static final String FILE = "file:";

    interface FrameSource extends Closeable {
        /** A frame exposed after this call began; IOException when the device stopped giving frames. */
        BufferedImage read() throws IOException;
    }

    interface Opener {
        /** The device opened in the camera's mode; IOException when it cannot be (busy), {@link Refused}: its why. */
        FrameSource open(DispenseConfig.UsbCamera cam) throws IOException;
    }

    interface Encoder {
        /** The frame at most {@code maxW} wide (0: as it is), encoded. */
        ApiServer.Response encode(BufferedImage img, int maxW) throws Exception;
    }

    /** An open refused with the whole why (the rules, or the camera did not take the mode). */
    static final class Refused extends IOException {
        private static final long serialVersionUID = 1L;

        Refused(String why) {
            super(why);
        }
    }

    private final Supplier<DispenseConfig.UsbCameraSettings> settings;
    private final BooleanSupplier simulated;
    private final Supplier<File> configDir;
    private final Opener devices;
    private final Encoder encoder;
    /** Guarded by this. */
    private final Map<String, Capture> captures = new HashMap<>();
    private boolean shut;

    /**
     * @param settings the engine's {@code usbCameras}, null while it is not running
     * @param simulated the controller of this connection is the simulator
     * @param devices opens a /dev/v4l/by-id/ device ({@link #openV4l2})
     */
    UsbCameras(Supplier<DispenseConfig.UsbCameraSettings> settings, BooleanSupplier simulated, Supplier<File> configDir,
            Opener devices, Encoder encoder) {
        this.settings = settings;
        this.simulated = simulated;
        this.configDir = configDir;
        this.devices = devices;
        this.encoder = encoder;
    }

    /** {@code <configDir>/instancelabs/sim-cameras}: the only place a {@code file:} device may name. */
    static File fixturesDir(File configDir) {
        return new File(new File(configDir, "instancelabs"), "sim-cameras");
    }

    /** GET /api/cameras' entries of the USB cameras that are set: {id, name, kind, present, why, width, height}. */
    List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        DispenseConfig.UsbCameraSettings s = settings.get();
        for (String id : IDS) {
            DispenseConfig.UsbCamera c = s == null ? null : s.get(id);
            if (c != null) {
                String why = unavailable(c);
                if (why == null) {
                    Capture cap = current(id, c);
                    why = cap == null ? null : cap.lastWhy();
                }
                out.add(Views.map("id", id, "name", NAMES.get(id), "kind", "usb", "present", why == null, "why", why,
                        "width", c.width, "height", c.height));
            }
        }
        return out;
    }

    /** GET /api/cameras/{machine|gauge}/frame.jpg: a frame captured after {@code after} (null: after this call). */
    ApiServer.Response frame(String id, int maxW, Long after) throws Exception {
        DispenseConfig.UsbCameraSettings s = settings.get();
        DispenseConfig.UsbCamera c = s == null ? null : s.get(id);
        if (c == null) {
            throw ApiException.notFound("no " + id + " camera here (GET /api/cameras; the setting usbCameras." + id + ")");
        }
        String why = unavailable(c);
        if (why != null) {
            throw cameraUnavailable(why);
        }
        long now = System.currentTimeMillis();
        long since = after == null ? now : after;
        requireWithinWait(since, now, s.frameWaitMs);
        Frame f = capture(id, c).await(since, now + s.frameWaitMs, s.idleReleaseMs);
        ApiServer.Response r = f.encoded(maxW, encoder);
        return ApiServer.Response.bytes(r.status, r.contentType, r.body).header("X-Frame-At-Ms", String.valueOf(f.at));
    }

    /** How long a frame request waits ({@code usbCameras.frameWaitMs}; top and bottom too). */
    long frameWaitMs() {
        DispenseConfig.UsbCameraSettings s = settings.get();
        return (s != null ? s : new DispenseConfig.UsbCameraSettings()).frameWaitMs;
    }

    /** 504 at once for an {@code after} further ahead than a request waits: no frame can come in time. */
    static void requireWithinWait(long after, long now, long waitMs) throws ApiException {
        if (after - now >= waitMs) {
            throw new ApiException(504, "no-new-frame", "after= is " + (after - now) + " ms ahead: a frame request "
                    + "waits at most usbCameras.frameWaitMs (" + waitMs + " ms)");
        }
    }

    static ApiException cameraUnavailable(String why) {
        return new ApiException(503, "camera-unavailable", why).with("why", why);
    }

    /** "refused: …" (the rules) or "not plugged in: … is gone", or null. */
    private String unavailable(DispenseConfig.UsbCamera c) {
        String refused = refusal(c, simulated.getAsBoolean(), fixturesDir(configDir.get()));
        return refused != null ? "refused: " + refused : gone(c);
    }

    /** "not plugged in: … is gone" when the device's path is gone (udev removes its by-id link), else null. */
    static String gone(DispenseConfig.UsbCamera c) {
        return c.device.startsWith(FILE) || Files.exists(Paths.get(c.device)) ? null
                : "not plugged in: " + c.device + " is gone";
    }

    private synchronized Capture current(String id, DispenseConfig.UsbCamera c) {
        Capture cap = captures.get(id);
        return cap != null && cap.serves(c) ? cap : null;
    }

    /** The camera's capture; a new one when its settings changed (the old one releases the device first). */
    private synchronized Capture capture(String id, DispenseConfig.UsbCamera c) throws ApiException {
        if (shut) {
            throw cameraUnavailable("the engine is stopping");
        }
        Capture cap = captures.get(id);
        if (cap == null || !cap.serves(c)) {
            if (cap != null) {
                cap.stop();
            }
            Opener opener = !c.device.startsWith(FILE) ? devices
                    : cam -> openStill(cam, simulated.getAsBoolean(), fixturesDir(configDir.get()));
            cap = new Capture(id, c, opener, cap);
            captures.put(id, cap);
        }
        return cap;
    }

    /** The engine stops: every capture releases its device. */
    synchronized void shutdown() {
        shut = true;
        for (Capture c : captures.values()) {
            c.stop();
        }
        captures.clear();
    }

    // ------------------------------------------------------------------------------------------
    // The rules (Infra 11-05 01:4x)
    // ------------------------------------------------------------------------------------------

    /**
     * Why the camera may not be opened in this session, or null: MJPG in a bounded size; off the simulator only
     * /dev/v4l/by-id/ devices; {@code file:} only simulated, a JPEG in the fixtures dir.
     */
    static String refusal(DispenseConfig.UsbCamera c, boolean simulated, File fixtures) {
        if (!"MJPG".equals(c.fourcc)) {
            return "fourcc must be MJPG: the cameras share USB hub 3-1 with top and bottom, and YUYV at these sizes "
                    + "overloads it";
        }
        if (!(c.width >= 160 && c.width <= 4096 && c.height >= 120 && c.height <= 3072)) {
            return "width must be 160..4096 and height 120..3072 (the mode the camera is opened in)";
        }
        String d = c.device;
        if (d != null && BY_ID.matcher(d).matches()) {
            return null;
        }
        if (d == null || !d.startsWith(FILE)) {
            return "the device must be a /dev/v4l/by-id/… path (a /dev/videoN renumbers when cameras are plugged in)"
                    + (simulated ? ", or file: a JPEG in " + fixtures : ": the real machine takes only these");
        }
        if (!simulated) {
            return "file: devices are for simulated sessions only (the controller is not the simulator): the real "
                    + "machine takes only /dev/v4l/by-id/… paths";
        }
        try {
            stillFile(d, fixtures);
            return null;
        }
        catch (Refused e) {
            return e.getMessage();
        }
    }

    /** The JPEG a {@code file:} device names: resolved in the fixtures dir, links followed, still in it; else why. */
    private static Path stillFile(String device, File fixtures) throws Refused {
        Path dir, f;
        try {
            dir = fixtures.toPath().toRealPath();
            f = dir.resolve(device.substring(FILE.length())).toRealPath();
        }
        catch (IOException | InvalidPathException e) {
            throw new Refused(device + ": no such file in " + fixtures);
        }
        if (!f.startsWith(dir) || f.equals(dir) || !Files.isRegularFile(f)) {
            throw new Refused(device + ": file: devices are JPEG files in " + fixtures + " only");
        }
        try (InputStream in = Files.newInputStream(f)) {
            if (Files.size(f) > MAX_STILL_BYTES || !isJpeg(in.readNBytes(2))) {
                throw new Refused(device + " is not a JPEG still (its bytes must start FF D8; at most 20 MB)");
            }
        }
        catch (Refused e) {
            throw e;
        }
        catch (IOException e) {
            throw new Refused(device + ": " + e.getMessage());
        }
        return f;
    }

    private static boolean isJpeg(byte[] b) {
        return b.length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
    }

    // ------------------------------------------------------------------------------------------
    // Frame sources
    // ------------------------------------------------------------------------------------------

    /** A {@code file:} device: its still JPEG is every frame; opened under the same rules as the write. */
    static FrameSource openStill(DispenseConfig.UsbCamera c, boolean simulated, File fixtures) throws IOException {
        String why = refusal(c, simulated, fixtures);
        if (why != null) {
            throw new Refused("refused: " + why);
        }
        byte[] jpg = Files.readAllBytes(stillFile(c.device, fixtures));
        BufferedImage img = isJpeg(jpg) ? decode(jpg) : null; // the bytes read, not only those checked
        if (img == null) {
            throw new Refused(c.device + " is not a JPEG still");
        }
        return new FrameSource() {
            @Override
            public BufferedImage read() {
                return img;
            }

            @Override
            public void close() {
            }
        };
    }

    /** OpenCV's decoder (the deployed JVM's ImageIO lacks JPEG plugins, 11-03). */
    static BufferedImage decode(byte[] jpg) {
        nu.pattern.OpenCV.loadLocally();
        MatOfByte buf = new MatOfByte(jpg);
        Mat m = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
        try {
            return m.empty() ? null : OpenCvUtils.toBufferedImage(m);
        }
        finally {
            m.release();
            buf.release();
        }
    }

    /**
     * A /dev/v4l/by-id/ device through OpenCV's V4L2 capture, as OpenPnP's OpenCvCamera and Process's tools open
     * them: the fourcc, the size and a one-frame buffer. Refused, never streamed, when the camera does not take the
     * mode (it would pick another, YUYV, on the shared hub).
     */
    static FrameSource openV4l2(DispenseConfig.UsbCamera c) throws IOException {
        nu.pattern.OpenCV.loadLocally();
        VideoCapture cap = new VideoCapture(c.device, Videoio.CAP_V4L2);
        try {
            if (!cap.isOpened()) {
                throw new IOException(c.device + " did not open");
            }
            String f = c.fourcc;
            cap.set(Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc(f.charAt(0), f.charAt(1), f.charAt(2), f.charAt(3)));
            cap.set(Videoio.CAP_PROP_FRAME_WIDTH, c.width);
            cap.set(Videoio.CAP_PROP_FRAME_HEIGHT, c.height);
            cap.set(Videoio.CAP_PROP_BUFFERSIZE, 1);
            int v = (int) cap.get(Videoio.CAP_PROP_FOURCC);
            String got = new String(new char[] {(char) (v & 0xff), (char) (v >> 8 & 0xff), (char) (v >> 16 & 0xff),
                    (char) (v >> 24 & 0xff)}) + " " + (int) cap.get(Videoio.CAP_PROP_FRAME_WIDTH) + "x"
                    + (int) cap.get(Videoio.CAP_PROP_FRAME_HEIGHT);
            if (!got.equals(c.mode())) {
                throw new Refused("the camera did not take " + c.mode() + " (it gave " + got + "): not streamed");
            }
        }
        catch (IOException | RuntimeException e) {
            cap.release();
            throw e;
        }
        return new FrameSource() {
            @Override
            public BufferedImage read() throws IOException {
                long start = System.nanoTime() / 1_000_000; // V4L2 buffer times are CLOCK_MONOTONIC, as nanoTime
                Mat m = new Mat();
                try {
                    for (int tries = 0; tries < 3; tries++) {
                        // the queue holds frames from before this call (one buffer asked, the driver may keep more):
                        // grab past them to one exposed after it began
                        for (int i = 0; i < 2 || cap.get(Videoio.CAP_PROP_POS_MSEC) < start && i < 8; i++) {
                            if (!cap.grab()) {
                                throw new IOException("no frame from " + c.device);
                            }
                        }
                        if (cap.retrieve(m) && !m.empty()) {
                            return OpenCvUtils.toBufferedImage(m);
                        }
                    }
                    throw new IOException("frames from " + c.device + " do not decode");
                }
                finally {
                    m.release();
                }
            }

            @Override
            public void close() {
                cap.release();
            }
        };
    }

    /** "name (pid N)" of a process other than this one with the device open (from /proc/<pid>/fd), or null. */
    static String holderOf(Path device) {
        Path real;
        try {
            real = device.toRealPath();
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
        long self = ProcessHandle.current().pid();
        File[] procs = new File("/proc").listFiles((d, n) -> n.chars().allMatch(Character::isDigit));
        for (File p : procs == null ? new File[0] : procs) {
            File[] fds = Long.parseLong(p.getName()) == self ? null : new File(p, "fd").listFiles();
            for (File fd : fds == null ? new File[0] : fds) { // null: not this user's to read
                try {
                    if (real.equals(Files.readSymbolicLink(fd.toPath()))) {
                        return new String(Files.readAllBytes(new File(p, "comm").toPath()), StandardCharsets.UTF_8)
                                .trim() + " (pid " + p.getName() + ")";
                    }
                }
                catch (IOException | RuntimeException e) {
                    // closed meanwhile
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // One camera's capture
    // ------------------------------------------------------------------------------------------

    /** A frame and its capture time (epoch ms), encoded once per width asked. */
    static final class Frame {
        final BufferedImage img;
        final long at;
        /** Guarded by this. */
        private final Map<Integer, ApiServer.Response> byWidth = new LinkedHashMap<>();

        Frame(BufferedImage img, long at) {
            this.img = img;
            this.at = at;
        }

        /** The frame at most {@code maxW} wide (0: whole), encoded at the first request of that width. */
        synchronized ApiServer.Response encoded(int maxW, Encoder enc) throws Exception {
            int w = maxW <= 0 || maxW >= img.getWidth() ? 0 : maxW;
            ApiServer.Response r = byWidth.get(w);
            if (r == null) {
                r = enc.encode(img, w);
                if (byWidth.size() >= WIDTHS_KEPT) {
                    byWidth.remove(byWidth.keySet().iterator().next());
                }
                byWidth.put(w, r);
            }
            return r;
        }
    }

    /**
     * One camera's capture: its thread opens the device at a request, reads a frame whenever a request waits for one
     * newer than the latest, and releases the device after the idle time, a failure or {@link #stop}.
     */
    static final class Capture {
        final String id;
        final DispenseConfig.UsbCamera cam;
        private final Opener opener;
        /** The capture this one replaced (its settings changed): its device is released before this one opens. */
        private final Capture replaced;
        // guarded by this:
        /** The running capture's thread, null while released. */
        private Thread thread;
        /** The last thread started: the next one waits for it to release the device. */
        private Thread last;
        private Frame latest;
        /** The last failure for the list (not "gone": that is read from the path), null after a frame. */
        private String lastWhy;
        private int attempts, failedAttempt;
        private String failedWhy;
        private long askedAtMs, idleMs;
        /** The {@code after} of each waiting request. */
        private final List<Long> waiting = new ArrayList<>();
        private boolean stopped;

        Capture(String id, DispenseConfig.UsbCamera cam, Opener opener, Capture replaced) {
            this.id = id;
            this.cam = cam;
            this.opener = opener;
            this.replaced = replaced;
        }

        boolean serves(DispenseConfig.UsbCamera c) {
            return cam.device.equals(c.device) && cam.mode().equals(c.mode());
        }

        synchronized String lastWhy() {
            return lastWhy;
        }

        /** Releases the device (it is being replaced, or the engine stops); requests waiting get 503. */
        synchronized void stop() {
            stopped = true;
            notifyAll();
        }

        /**
         * The latest frame captured after {@code after}, read for this request if need be (the device opened if it
         * is released); 503 when the open or a read fails, 504 when none comes by {@code deadline}.
         */
        synchronized Frame await(long after, long deadline, long idleMs) throws ApiException, InterruptedException {
            this.idleMs = idleMs;
            askedAtMs = System.currentTimeMillis();
            if (thread == null) {
                if (stopped) {
                    throw cameraUnavailable("the " + id + " camera's settings changed: ask again");
                }
                Thread before = last;
                int attempt = ++attempts;
                thread = last = new Thread(() -> run(before, attempt), "il-usb-camera-" + id);
                thread.setDaemon(true);
                thread.start();
            }
            int mine = attempts;
            waiting.add(after);
            notifyAll();
            try {
                while (true) {
                    if (latest != null && latest.at > after) {
                        return latest;
                    }
                    if (failedAttempt >= mine) {
                        throw cameraUnavailable(failedWhy);
                    }
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) {
                        throw new ApiException(504, "no-new-frame", "the " + id + " camera gave no frame after " + after
                                + " within usbCameras.frameWaitMs");
                    }
                    wait(left);
                }
            }
            finally {
                waiting.remove(Long.valueOf(after));
            }
        }

        private void run(Thread before, int attempt) {
            join(before);
            join(replaced == null ? null : replaced.lastThread());
            FrameSource src = null;
            boolean opened = false;
            try {
                src = opener.open(cam);
                opened = true;
                Logger.info("USB camera {}: {} opened ({})", id, cam.device, cam.mode());
                for (int i = 0; i < DROP_AFTER_OPEN; i++) {
                    src.read();
                }
                while (awaitDemand(attempt)) {
                    BufferedImage img = src.read();
                    publish(img, System.currentTimeMillis());
                }
            }
            catch (Throwable t) {
                String gone = gone(cam);
                end(attempt, gone != null ? gone : describe(t, opened), gone == null);
            }
            finally {
                if (src != null) {
                    try {
                        src.close();
                    }
                    catch (Throwable t) {
                        Logger.warn("USB camera {}: closing {}: {}", id, cam.device, t.toString());
                    }
                }
            }
        }

        /** Why the open or a read failed: the mode refused, busy (naming the holder), or no more frames. */
        private String describe(Throwable t, boolean opened) {
            if (t instanceof Refused) {
                return t.getMessage();
            }
            if (opened) {
                return "the camera stopped giving pictures (" + (t.getMessage() != null ? t.getMessage() : t) + ")";
            }
            String holder = holderOf(Paths.get(cam.device));
            return "busy: " + (holder != null ? holder + " has it open" : "another program has it open");
        }

        /**
         * Waits until a request waits for a frame newer than the latest: true to read it; false when the capture
         * ended (idle, stopped).
         */
        private synchronized boolean awaitDemand(int attempt) throws InterruptedException {
            while (true) {
                if (stopped) {
                    end(attempt, "the " + id + " camera's settings changed: ask again", false);
                    return false;
                }
                long now = System.currentTimeMillis();
                Long want = unserved();
                if (want == null && now - askedAtMs >= idleMs) {
                    Logger.info("USB camera {}: {} released (no request for {} ms)", id, cam.device, idleMs);
                    end(attempt, null, false);
                    return false;
                }
                if (want != null && want < now) {
                    return true;
                }
                wait(want != null ? want + 1 - now : askedAtMs + idleMs - now);
            }
        }

        /** The earliest {@code after} of a waiting request that the latest frame does not serve, or null. */
        private Long unserved() {
            Long min = null;
            for (long a : waiting) {
                if ((latest == null || latest.at <= a) && (min == null || a < min)) {
                    min = a;
                }
            }
            return min;
        }

        private synchronized void publish(BufferedImage img, long at) {
            latest = new Frame(img, at);
            lastWhy = null;
            notifyAll();
        }

        /** The capture ends: released, and with {@code why} the requests waiting on this attempt get 503. */
        private synchronized void end(int attempt, String why, boolean listed) {
            if (why != null) {
                if (!why.equals(failedWhy)) {
                    Logger.info("USB camera {}: {}", id, why);
                }
                failedAttempt = attempt;
                failedWhy = why;
                lastWhy = listed ? why : null;
            }
            latest = null;
            if (thread == Thread.currentThread()) {
                thread = null;
            }
            notifyAll();
        }

        private synchronized Thread lastThread() {
            return last;
        }

        private static void join(Thread t) {
            if (t != null && t != Thread.currentThread()) {
                try {
                    t.join(5000);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
