package org.openpnp.instancelabs.board;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.openpnp.instancelabs.dispense.DispenseDb;
import org.openpnp.instancelabs.dispense.PadType;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.dispense.PadTypeStatus;

import org.openpnp.instancelabs.kicad.BoardFrame;
import org.openpnp.instancelabs.kicad.Deadline;
import org.openpnp.instancelabs.kicad.ImportProposal;
import org.openpnp.instancelabs.kicad.ImportRules;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.instancelabs.kicad.KicadParser;
import org.openpnp.instancelabs.kicad.KicadPcb;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.Configuration;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/** The KiCad fixtures of src/test/resources/kicad (design §6.1) for the art tests: parsed, framed, proposed, drawn. */
final class ArtFixtures {
    static final String FTP = "ftp.kicad_pcb.gz";
    static final String SECONDARY = "secondary-fid-pcb.kicad_pcb";
    static final String NANO = "NanoV3.3.kicad_pcb.gz";
    static final String MOBO = "mobo.kicad_pcb.gz";
    static final String PI7 = "pi-interface-k7.kicad_pcb";
    static final String PI10 = "pi-interface-k10.kicad_pcb.gz";
    static final String FTP0402 = "ftp0402.kicad_pcb.gz";
    static final String[] BOARDS = {FTP, SECONDARY, NANO, MOBO, PI7, PI10, FTP0402};

    /** A parsed board, its frame, the default proposal and its art. */
    static final class Drawn {
        final String name;
        final byte[] bytes;
        final KicadPcb pcb;
        final BoardFrame frame;
        final ImportProposal proposal;
        final BoardArtBuilder.Result result;

        Drawn(String name, byte[] bytes, KicadPcb pcb, BoardFrame frame, ImportProposal proposal,
                BoardArtBuilder.Result result) {
            this.name = name;
            this.bytes = bytes;
            this.pcb = pcb;
            this.frame = frame;
            this.proposal = proposal;
            this.result = result;
        }

        BoardArt art() {
            return result.art;
        }
    }

    private ArtFixtures() {
    }

    static byte[] bytes(String name) throws IOException {
        try (InputStream raw = ArtFixtures.class.getResourceAsStream("/kicad/" + name)) {
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

    static Drawn draw(String name) throws Exception {
        return draw(name, bytes(name), new BoardArtBuilder());
    }

    static Drawn draw(String name, byte[] bytes, BoardArtBuilder builder) throws Exception {
        Deadline d = Deadline.none();
        KicadPcb pcb = KicadParser.read(bytes, d);
        BoardFrame frame = BoardFrame.of(pcb, d);
        ImportProposal p = new ImportRules().propose(pcb, frame, d);
        return new Drawn(name, bytes, pcb, frame, p, builder.build(pcb, frame, p, d));
    }

    /** A board written in a test: the (kicad_pcb …) wrapper around the given items. */
    static byte[] board(int version, String items) {
        String s = "(kicad_pcb (version " + version + ") (generator \"test\")\n" + items + "\n)\n";
        return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The bench's ftp.board.xml (F/) in the loaded configuration, whose packages have no pads (as the live
     * packages.xml): its parts are added first, then the board is read from a copy in dir.
     */
    static Board ftpBoardXml(File dir) throws Exception {
        Configuration c = Configuration.get();
        String[][] parts = {{"R_0603_1608Metric-R_Small", "R_0603_1608Metric"},
                {"LED_0603_1608Metric-LED_Small", "LED_0603_1608Metric"},
                {"Fiducial_1mm_Mask3mm-Fiducial", "Fiducial_1mm_Mask3mm"}};
        for (String[] p : parts) {
            Package pkg = c.getPackage(p[1]);
            if (pkg == null) {
                pkg = new Package(p[1]);
                c.addPackage(pkg);
            }
            if (c.getPart(p[0]) == null) {
                Part part = new Part(p[0]);
                part.setPackage(pkg);
                c.addPart(part);
            }
        }
        File f = new File(dir, "ftp.board.xml");
        Files.write(f.toPath(), bytes("ftp.board.xml"));
        return c.getBoard(f);
    }

    /** The seeded pad types (provisional), as the planner of a fresh database resolves them. */
    static PadTypeResolver seedResolver() {
        List<PadType> types = new ArrayList<>();
        for (PadType t : DispenseDb.seedPadTypes()) {
            t.version = 1;
            t.status = PadTypeStatus.PROVISIONAL;
            types.add(t);
        }
        return new PadTypeResolver(types, null);
    }

    /** An OpenPnP board with the proposal's placements (no parts), as an import writes it. */
    static Board boardOf(ImportProposal p, File file) {
        Board b = new Board(file);
        b.setName(file.getName());
        b.setDimensions(new Location(LengthUnit.Millimeters, p.widthMm, p.heightMm, 0, 0));
        for (ImportProposal.PlacementP x : p.placements) {
            Placement pl = new Placement(x.id);
            pl.setType("Fiducial".equals(x.type) ? Placement.Type.Fiducial : Placement.Type.Placement);
            pl.setSide(Side.Top);
            pl.setLocation(new Location(LengthUnit.Millimeters, x.x, x.y, 0, x.rotation));
            pl.setEnabled(x.enabled);
            b.addPlacement(pl);
        }
        return b;
    }

    /** Every subpath of a path, as polygons or polylines (arcs by their end points). */
    static List<ArtPath.Sub> subs(String d) {
        return ArtPath.parse(d);
    }

    /** The box of every point of the paths. */
    static double[] box(List<String> paths) {
        double[] box = KGeom.emptyBox();
        for (String d : paths) {
            for (ArtPath.Sub s : ArtPath.parse(d)) {
                KGeom.extendPts(box, s.pts);
            }
        }
        return box;
    }

    /** Every path of the art (layers, outline; not the thumbnail). */
    static List<String> paths(BoardArt a) {
        List<String> l = new ArrayList<>();
        l.add(a.outline.d);
        l.add(a.outline.cutouts);
        l.add(a.layers.cu.fill);
        l.add(a.layers.mask.open);
        l.add(a.layers.exposed.fill);
        l.add(a.layers.paste.fill);
        l.add(a.layers.silk.fill);
        l.add(a.layers.holes.pth);
        l.add(a.layers.holes.npth);
        l.add(a.layers.holes.via);
        for (List<BoardArt.Stroke> ls : java.util.Arrays.asList(a.layers.cu.tracks, a.layers.silk.lines,
                a.layers.fab.lines)) {
            for (BoardArt.Stroke s : ls) {
                l.add(s.d);
            }
        }
        return l;
    }

    /**
     * True when (x, y) lies inside one closed subpath of d: a circle (M and two half arcs) by its radius, any other
     * shape by its vertices as a polygon (a rounded rectangle's tangent points enclose its centre).
     */
    static boolean inside(String d, double x, double y) {
        for (ArtPath.Sub s : ArtPath.parse(d)) {
            double[] c = circle(s);
            if (c != null ? Math.hypot(x - c[0], y - c[1]) <= c[2]
                    : s.closed && s.pts.length >= 6 && KGeom.pointInPolygon(x, y, s.pts)) {
                return true;
            }
        }
        return false;
    }

    /** {cx, cy, r} of a subpath written by ArtPath.circle, else null. */
    static double[] circle(ArtPath.Sub s) {
        double[] p = s.pts;
        if (!s.closed || p.length != 6 || Math.abs(p[0] - p[4]) > 1e-9 || Math.abs(p[1] - p[5]) > 1e-9) {
            return null;
        }
        return new double[] {(p[0] + p[2]) / 2, (p[1] + p[3]) / 2, Math.hypot(p[0] - p[2], p[1] - p[3]) / 2};
    }

    /** The circles of a path ({cx, cy, r} each). */
    static List<double[]> circles(String d) {
        List<double[]> l = new ArrayList<>();
        for (ArtPath.Sub s : ArtPath.parse(d)) {
            double[] c = circle(s);
            if (c != null) {
                l.add(c);
            }
        }
        return l;
    }
}
