package org.openpnp.instancelabs.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;

import org.junit.jupiter.api.Test;

/**
 * The top camera snapshot must never answer an empty 200. Real mode on the bench ThinkPad (2026-10-28) had no JPEG
 * writer in the JVM, and ImageIO.write answers false without an exception in that case.
 */
public class CameraSnapshotEncodingTest {
    private static BufferedImage frame() {
        BufferedImage img = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        img.setRGB(10, 10, 0xffffff);
        return img;
    }

    @Test
    public void jpegWhenAvailable() throws Exception {
        ApiServer.Response r = new MachineEndpoints(null).encodeFrame(frame());
        assertEquals(200, r.status);
        assertEquals("image/jpeg", r.contentType);
        assertTrue(r.body.length > 0);
    }

    /** A 1920 x 1080 frame with a bench's texture (gradients, edges, fine noise): what the cameras deliver. */
    private static BufferedImage fullHd() {
        BufferedImage img = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);
        java.util.Random r = new java.util.Random(7);
        for (int y = 0; y < 1080; y++) {
            for (int x = 0; x < 1920; x++) {
                int pad = ((x / 40) + (y / 30)) % 7 == 0 ? 90 : 0;
                int g = Math.min(255, 40 + x / 12 + y / 10 + pad + r.nextInt(12));
                img.setRGB(x, y, (Math.min(255, g + 20) << 16) | (g << 8) | Math.max(0, g - 30));
            }
        }
        return img;
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff;
    }

    /** ImageIO's JPEG writers off, as the deployed JVM (11-03: frames went out as 1.4-1.8 MB PNGs). */
    static <T> T withoutImageIoJpeg(java.util.concurrent.Callable<T> body) throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> removed = new ArrayList<>();
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg"); it.hasNext();) {
            removed.add(it.next().getOriginatingProvider());
        }
        removed.forEach(spi -> registry.deregisterServiceProvider(spi, ImageWriterSpi.class));
        try {
            return body.call();
        }
        finally {
            removed.forEach(spi -> registry.registerServiceProvider(spi, ImageWriterSpi.class));
        }
    }

    /** The deployed JVM has no ImageIO JPEG writer: OpenCV (bundled with OpenPnP) encodes the JPEG instead. */
    @Test
    public void jpegThroughOpenCvWhenTheJvmHasNoJpegWriter() throws Exception {
        ApiServer.Response r = withoutImageIoJpeg(() -> new MachineEndpoints(null).encodeFrame(frame()));
        assertEquals(200, r.status);
        assertEquals("image/jpeg", r.contentType);
        assertTrue(isJpeg(r.body));
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(r.body));
        assertEquals(64, back.getWidth());
        assertEquals(36, back.getHeight());
    }

    /** A full 1920 x 1080 frame stays small enough for a 2-5 per second view over the tailnet, both encoders. */
    @Test
    public void aFullHdFrameIsAJpegUnderTheBound() throws Exception {
        BufferedImage img = fullHd();
        ApiServer.Response imageIo = new MachineEndpoints(null).encodeFrame(img);
        ApiServer.Response openCv = withoutImageIoJpeg(() -> new MachineEndpoints(null).encodeFrame(img));
        for (ApiServer.Response r : new ApiServer.Response[] {imageIo, openCv}) {
            assertEquals("image/jpeg", r.contentType);
            assertTrue(isJpeg(r.body));
            assertTrue(r.body.length < MachineEndpoints.FRAME_JPEG_BOUND_BYTES, r.body.length + " bytes");
        }
        assertEquals(0.85f, MachineEndpoints.JPEG_QUALITY, 0, "the frames' quality is set, not the encoder's default");
    }

    /** No JPEG encoder at all (OpenCV missing too): PNG, never an empty 200. */
    @Test
    public void pngOnlyWhenNoJpegEncoderAtAll() throws Exception {
        MachineEndpoints.openCvJpeg = false;
        try {
            ApiServer.Response r = withoutImageIoJpeg(() -> new MachineEndpoints(null).encodeFrame(frame()));
            assertEquals(200, r.status);
            assertEquals("image/png", r.contentType);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(r.body));
            assertEquals(64, back.getWidth());
            assertEquals(0xffffff, back.getRGB(10, 10) & 0xffffff);
        }
        finally {
            MachineEndpoints.openCvJpeg = true;
        }
    }
}
