package org.openpnp.instancelabs.dispense;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.openpnp.util.OpenCvUtils;
import org.pmw.tinylog.Logger;

/**
 * The one JPEG encoder of camera frames and stored photos: ImageIO's writer at the given quality, else OpenCV's
 * {@code imencode} (bundled with OpenPnP; the deployed JVM has no ImageIO JPEG writer, 11-03: frames went out as
 * 1.4-1.8 MB PNGs and stored photos failed. BR-105 found why: the JDK's JPEG plugin binds to the cameras' own libjpeg
 * and ImageIO drops it). Null when neither works; the caller decides (a frame falls back to PNG).
 * In memory only (no temp file per frame); thread safe.
 */
public final class JpegEncoder {
    private JpegEncoder() {
    }

    /** Tests only: one encoder off. */
    public static volatile boolean imageIo = true;
    public static volatile boolean openCv = true;
    private static volatile boolean openCvFailedLogged;

    /** JPEG bytes at {@code quality} (0..1), or null when no encoder works. */
    public static byte[] encode(BufferedImage img, float quality) {
        byte[] b = imageIo ? viaImageIo(img, quality) : null;
        return b != null ? b : openCv ? viaOpenCv(img, quality) : null;
    }

    private static byte[] viaImageIo(BufferedImage img, float quality) {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter w = it.hasNext() ? it.next() : null; // next() is null when the writer cannot be created
        if (w == null) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            w.setOutput(ios);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            w.write(null, new IIOImage(img, null, null), p);
        }
        catch (Exception e) {
            return null;
        }
        finally {
            w.dispose();
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    private static byte[] viaOpenCv(BufferedImage img, float quality) {
        Mat m = null;
        MatOfByte buf = null;
        MatOfInt params = null;
        try {
            m = OpenCvUtils.toMat(img); // first: OpenCvUtils loads OpenCV's native library (BGR order, as imencode wants)
            buf = new MatOfByte();
            params = new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, Math.round(quality * 100));
            byte[] b = Imgcodecs.imencode(".jpg", m, buf, params) ? buf.toArray() : null;
            return b != null && b.length > 0 ? b : null;
        }
        catch (Throwable t) {
            if (!openCvFailedLogged) {
                openCvFailedLogged = true;
                Logger.warn("JPEG: OpenCV's encoder failed ({}); frames go out as PNG", t.toString());
            }
            if (t instanceof LinkageError) {
                openCv = false; // no native library: no point trying on every frame
            }
            return null;
        }
        finally {
            if (m != null) {
                m.release();
            }
            if (buf != null) {
                buf.release();
            }
            if (params != null) {
                params.release();
            }
        }
    }
}
