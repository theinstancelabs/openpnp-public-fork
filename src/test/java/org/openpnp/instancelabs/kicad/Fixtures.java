package org.openpnp.instancelabs.kicad;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/** The KiCad fixtures of src/test/resources/kicad (design §6.1); *.gz files are read through GZIPInputStream. */
final class Fixtures {
    static final String FTP = "ftp.kicad_pcb.gz";
    static final String SECONDARY = "secondary-fid-pcb.kicad_pcb";
    static final String NANO = "NanoV3.3.kicad_pcb.gz";
    static final String MOBO = "mobo.kicad_pcb.gz";
    static final String PI7 = "pi-interface-k7.kicad_pcb";
    static final String PI10 = "pi-interface-k10.kicad_pcb.gz";
    static final String FTP0402 = "ftp0402.kicad_pcb.gz";
    static final String[] BOARDS = {FTP, SECONDARY, NANO, MOBO, PI7, PI10, FTP0402};

    /** A parsed board, its frame and the default proposal. */
    static final class Loaded {
        final KicadPcb pcb;
        final BoardFrame frame;
        final ImportProposal proposal;

        Loaded(KicadPcb pcb, BoardFrame frame, ImportProposal proposal) {
            this.pcb = pcb;
            this.frame = frame;
            this.proposal = proposal;
        }
    }

    private Fixtures() {
    }

    static byte[] bytes(String name) throws IOException {
        try (InputStream raw = Fixtures.class.getResourceAsStream("/kicad/" + name)) {
            if (raw == null) {
                throw new IOException("missing fixture " + name);
            }
            InputStream in = name.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0;) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    static Loaded load(String name) throws Exception {
        return load(bytes(name), new ImportRules());
    }

    static Loaded load(String name, ImportRules rules) throws Exception {
        return load(bytes(name), rules);
    }

    static Loaded load(byte[] bytes, ImportRules rules) throws Exception {
        Deadline d = Deadline.none();
        KicadPcb pcb = KicadParser.read(bytes, d);
        BoardFrame frame = BoardFrame.of(pcb, d);
        return new Loaded(pcb, frame, rules.propose(pcb, frame, d));
    }

    /** A board written in a test: the (kicad_pcb …) wrapper around the given items. */
    static Loaded board(int version, String items) throws Exception {
        return board(version, items, new ImportRules());
    }

    static Loaded board(int version, String items, ImportRules rules) throws Exception {
        String generator = version >= KicadPcb.V_KICAD6 ? " (generator \"test\")" : ""; // KiCad 5 has (host …)
        String s = "(kicad_pcb (version " + version + ")" + generator + "\n" + items + "\n)\n";
        return load(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), rules);
    }
}
