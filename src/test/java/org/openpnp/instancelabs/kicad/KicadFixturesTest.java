package org.openpnp.instancelabs.kicad;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/** The fixtures keep their licences and drop the marks they must not carry (design §6.1, §13 item 9). */
public class KicadFixturesTest {
    static final String[] ALL = {Fixtures.FTP, Fixtures.SECONDARY, Fixtures.NANO, Fixtures.MOBO, Fixtures.PI7,
            Fixtures.PI10, Fixtures.FTP0402, "NanoV3.3-F_Paste.gbr", "NanoV3.3-PTH.drl", "NanoV3.3-NPTH.drl",
            "ftp.board.xml"};

    @Test
    public void noMarksInTheFixtures() throws Exception {
        for (String f : ALL) { // the boards, the oracles and ftp.board.xml: only LICENSE.txt names the projects
            String s = new String(Fixtures.bytes(f), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            assertFalse(s.contains("opulo"), f);
            assertFalse(s.contains("lumenpnp"), f);
        }
        for (String f : Fixtures.BOARDS) {
            String s = new String(Fixtures.bytes(f), StandardCharsets.UTF_8);
            if (!f.equals(Fixtures.NANO) && !f.equals(Fixtures.MOBO)) {
                assertFalse(s.contains("(model "), f + ": the 3-D model references are removed");
            }
        }
    }

    @Test
    public void everyFixtureIsCredited() throws Exception {
        String licence = new String(Fixtures.bytes("LICENSE.txt"), StandardCharsets.UTF_8);
        for (String f : ALL) {
            assertTrue(licence.contains(f), f + " is not in LICENSE.txt");
        }
        assertTrue(licence.contains("LumenPnP is (c) by Opulo"), "the projects' notice");
        assertTrue(licence.contains("CERN-OHL-W v2"));
        assertTrue(licence.contains("CC BY-SA 4.0"));
    }

    @Test
    public void largeFixturesAreGzipped() throws Exception {
        File dir = new File(Fixtures.class.getResource("/kicad/LICENSE.txt").toURI()).getParentFile();
        for (File f : dir.listFiles()) {
            assertTrue(f.getName().endsWith(".gz") || Files.size(f.toPath()) <= 1_000_000, f.getName());
        }
    }
}
