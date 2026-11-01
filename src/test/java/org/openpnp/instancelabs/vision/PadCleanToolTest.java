package org.openpnp.instancelabs.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class PadCleanToolTest {
    @TempDir
    Path tmp;

    @Test
    public void printsJsonLinePerFile() throws Exception {
        double sx = PadCleanCheckTest.BENCH_X;
        double sy = PadCleanCheckTest.BENCH_Y;
        File clean = tmp.resolve("clean.png").toFile();
        File dirty = tmp.resolve("dirty.png").toFile();
        File missing = tmp.resolve("missing.png").toFile();
        ImageIO.write(Synth.render(PadCleanCheckTest.lumen(sx, sy, 0, 0, 0)), "png", clean);
        ImageIO.write(Synth.render(PadCleanCheckTest.lumen(sx, sy, 0, 0, 0.5)), "png", dirty);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = PadCleanTool.run(new String[] {"--scale", sx + "," + sy, clean.getPath(),
                dirty.getPath(), missing.getPath()}, new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));
        String text = out.toString("UTF-8");
        System.out.println("[pad-clean] PadCleanTool output:\n" + text);
        assertEquals(1, code, err.toString("UTF-8"));
        String[] lines = text.trim().split("\n");
        assertEquals(3, lines.length);
        JsonObject c = new JsonParser().parse(lines[0]).getAsJsonObject();
        JsonObject d = new JsonParser().parse(lines[1]).getAsJsonObject();
        JsonObject m = new JsonParser().parse(lines[2]).getAsJsonObject();
        assertEquals(clean.getPath(), c.get("file").getAsString());
        assertTrue(c.get("clean").getAsBoolean());
        assertTrue(c.get("brightFraction").getAsDouble() >= 0.95);
        assertFalse(d.get("clean").getAsBoolean());
        // 320x240 frame: centre (160, 120), box 75 x 73 px.
        assertEquals(123, d.get("x0").getAsInt());
        assertEquals(84, d.get("y0").getAsInt());
        assertEquals(75, d.get("w").getAsInt());
        assertEquals(73, d.get("h").getAsInt());
        assertEquals(missing.getPath(), m.get("file").getAsString());
        assertTrue(m.has("error"));
    }

    @Test
    public void usageErrors() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream o = new PrintStream(out, true, "UTF-8");
        PrintStream e = new PrintStream(err, true, "UTF-8");
        assertEquals(2, PadCleanTool.run(new String[] {"a.jpg"}, o, e));
        assertEquals(2, PadCleanTool.run(new String[] {"--scale", "0.01"}, o, e));
        assertEquals(2, PadCleanTool.run(new String[] {"--scale", "1,1,1", "a.jpg"}, o, e));
        assertEquals(2, PadCleanTool.run(new String[] {"--scale", "0", "a.jpg"}, o, e));
        assertEquals(2, PadCleanTool.run(new String[] {"--scale", "1", "--bogus", "a.jpg"}, o, e));
        assertEquals(2, PadCleanTool.run(
                new String[] {"--scale", "0.01", "--gray-level", "255", "a.jpg"}, o, e));
        assertEquals(0, PadCleanTool.run(new String[] {"--help"}, o, e));
        assertTrue(new String(err.toByteArray(), StandardCharsets.UTF_8).contains("usage"));
    }
}
