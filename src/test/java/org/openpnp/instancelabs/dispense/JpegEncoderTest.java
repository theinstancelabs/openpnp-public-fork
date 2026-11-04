package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The one JPEG encoder of camera frames and stored photos (#23 review): ImageIO's writer, else OpenCV's (the deployed
 * JVM has no ImageIO JPEG writer, 11-03), each tested alone: the colours survive (no red/blue swap), the quality is
 * the set one (the first value of the luminance quantisation table), and photos are JPEG without ImageIO's writer.
 */
public class JpegEncoderTest {
    @AfterEach
    public void bothOn() {
        JpegEncoder.imageIo = true;
        JpegEncoder.openCv = true;
    }

    static BufferedImage redBlock() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                img.setRGB(x, y, 0xff0000);
            }
        }
        return img;
    }

    /** The first value of the first quantisation table (DQT) of a JPEG. */
    static int firstQuant(byte[] b) {
        for (int i = 0; i + 5 < b.length; i++) {
            if ((b[i] & 0xff) == 0xff && (b[i + 1] & 0xff) == 0xdb) {
                return b[i + 5] & 0xff;
            }
        }
        return -1;
    }

    void assertRedJpeg(byte[] jpg) throws Exception {
        assertNotNull(jpg);
        assertTrue((jpg[0] & 0xff) == 0xff && (jpg[1] & 0xff) == 0xd8, "a JPEG");
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(jpg));
        int rgb = back.getRGB(32, 32);
        assertTrue(((rgb >> 16) & 0xff) > 200 && (rgb & 0xff) < 50, "red stays red: " + Integer.toHexString(rgb));
        assertEquals(5, firstQuant(jpg), "quality 0.85 (the default writer gives 8, OpenCV's default 2)");
    }

    @Test
    public void imageIoAloneKeepsColoursAndQuality() throws Exception {
        JpegEncoder.openCv = false;
        assertRedJpeg(JpegEncoder.encode(redBlock(), 0.85f));
    }

    @Test
    public void openCvAloneKeepsColoursAndQuality() throws Exception {
        JpegEncoder.imageIo = false;
        assertRedJpeg(JpegEncoder.encode(redBlock(), 0.85f));
    }

    @Test
    public void neitherGivesNull() {
        JpegEncoder.imageIo = false;
        JpegEncoder.openCv = false;
        assertNull(JpegEncoder.encode(redBlock(), 0.85f));
    }

    /** Stored photos (dose blocks, tip pictures) were ImageIO-only: they threw on the deployed JVM. */
    @Test
    public void photosAreJpegWithoutImageIosWriter() throws Exception {
        JpegEncoder.imageIo = false;
        assertRedJpeg(PhotoStore.jpeg(redBlock()));
    }
}
