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

    @Test
    public void pngWhenTheJvmHasNoJpegWriter() throws Exception {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<ImageWriterSpi> removed = new ArrayList<>();
        for (Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg"); it.hasNext();) {
            removed.add(it.next().getOriginatingProvider());
        }
        removed.forEach(spi -> registry.deregisterServiceProvider(spi, ImageWriterSpi.class));
        try {
            ApiServer.Response r = new MachineEndpoints(null).encodeFrame(frame());
            assertEquals(200, r.status);
            assertEquals("image/png", r.contentType);
            BufferedImage back = ImageIO.read(new ByteArrayInputStream(r.body));
            assertEquals(64, back.getWidth());
            assertEquals(0xffffff, back.getRGB(10, 10) & 0xffffff);
        }
        finally {
            removed.forEach(spi -> registry.registerServiceProvider(spi, ImageWriterSpi.class));
        }
    }
}
