package org.openpnp.instancelabs.board;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openpnp.instancelabs.board.ArtPath.Shape;
import org.openpnp.instancelabs.dispense.BoardPadExtractor;
import org.openpnp.instancelabs.dispense.PadGeometry;
import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.instancelabs.kicad.KGeom;
import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Footprint;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/**
 * Board art for a board without a KiCad drawing (design §4.3): hand-made board files such as ftp.board.xml before an
 * attach, and OpenPnP job boards. The outline is the board's dimensions (else the hull of its pads grown 2 mm), the
 * pads are the planner's own (footprint pads, else the pad type's layout), the fiducials come from their packages.
 * No tracks, zones, silkscreen or holes. Reads the board only: call it where the board was read (§3.6).
 */
public final class BoardArtFallback {
    /** Mask openings around the pads (mm). */
    static final double MASK_MM = 0.05;
    /** Part boxes around their pads (mm). */
    static final double PART_MM = 0.3;
    /** The hull of the pads, grown by this, when the board has no dimensions (mm). */
    static final double HULL_MM = 2.0;
    static final double DEFAULT_FID_COPPER = 1.0;
    static final double DEFAULT_FID_MASK = 3.0;

    private static final Pattern FID_SIZE = Pattern.compile("(?i)fiducial[_-]?(\\d+(?:\\.\\d+)?)mm");
    private static final Pattern MASK_SIZE = Pattern.compile("(?i)mask(\\d+(?:\\.\\d+)?)mm");

    private BoardArtFallback() {
    }

    /**
     * @param resolver the planner's pad types (pads without footprint pads take their pad type's layout); null:
     *        footprint pads only
     */
    public static BoardArt build(Board board, PadTypeResolver resolver) {
        BoardArt art = new BoardArt();
        art.kind = BoardArt.KIND_FALLBACK;
        art.origin = null;
        art.imported = null;
        File file = board.getFile();
        art.source.name = file == null ? board.getName() : file.getName();
        art.source.boardFile = file == null ? null : file.getAbsolutePath();

        List<PadGeometry> pads = pads(board, resolver);
        Map<String, List<PadGeometry>> byPlacement = new HashMap<>();
        List<Shape> copper = new ArrayList<>();
        List<Shape> openings = new ArrayList<>(); // for the thumbnail: only those visibly wider than their copper
        ArtPath cu = new ArtPath();
        ArtPath mask = new ArtPath();
        double[] hull = KGeom.emptyBox();
        KGeom.Pts corners = new KGeom.Pts();
        for (PadGeometry g : pads) {
            byPlacement.computeIfAbsent(g.placementId, k -> new ArrayList<>()).add(g);
            double r = Math.min(g.wMm, g.hMm) * Math.max(0, Math.min(100, g.roundness)) / 200;
            Shape s = Shape.rect(g.xMm, g.yMm, g.wMm, g.hMm, r, g.rotationDeg);
            Shape o = Shape.rect(g.xMm, g.yMm, g.wMm + 2 * MASK_MM, g.hMm + 2 * MASK_MM, r + MASK_MM, g.rotationDeg);
            s.emit(cu);
            o.emit(mask);
            copper.add(s);
            for (double[] c : corners(g.xMm, g.yMm, g.wMm, g.hMm, g.rotationDeg)) {
                corners.add(c[0], c[1]);
                KGeom.extend(hull, c[0], c[1]);
            }
        }
        for (Placement p : board.getPlacements()) {
            if (p.getType() != Placement.Type.Fiducial || p.getSide() != Side.Top || !p.isEnabled()) {
                continue;
            }
            Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            double[] d = fiducialSize(p.getPart());
            BoardArt.FidArt f = new BoardArt.FidArt();
            f.name = p.getId();
            f.bx = KGeom.round6(l.getX());
            f.by = KGeom.round6(l.getY());
            f.copperMm = d[0];
            f.maskMm = d[1];
            art.fiducials.add(f);
            Shape s = Shape.circle(l.getX(), l.getY(), d[0] / 2);
            Shape o = Shape.circle(l.getX(), l.getY(), d[1] / 2);
            s.emit(cu);
            o.emit(mask);
            copper.add(s);
            openings.add(o);
            double[] ring = KGeom.circle(l.getX(), l.getY(), d[1] / 2, ArtPath.THUMB_MM);
            for (int i = 0; i + 1 < ring.length; i += 2) {
                corners.add(ring[i], ring[i + 1]);
            }
        }

        // the outline: the board's dimensions, else the hull of the pads and fiducials grown 2 mm
        Location dim = board.getDimensions() == null ? null
                : board.getDimensions().convertToUnits(LengthUnit.Millimeters);
        double[] outline;
        if (dim != null && dim.getX() > 0 && dim.getY() > 0) {
            outline = new double[] {0, 0, dim.getX(), 0, dim.getX(), dim.getY(), 0, dim.getY()};
        }
        else if (corners.size() >= 3) {
            outline = grownHull(corners.toArray(), HULL_MM);
        }
        else {
            outline = new double[] {0, 0, 10, 0, 10, 10, 0, 10};
        }
        double[] box = KGeom.emptyBox();
        KGeom.extendPts(box, outline);
        art.size = new double[] {KGeom.round6(box[2] - box[0]), KGeom.round6(box[3] - box[1])};
        ArtPath od = new ArtPath().polygon(outline);
        art.outline.d = od.toString();
        art.outline.closed = true;
        art.layers.cu.fill = cu.toString();
        art.layers.exposed.fill = cu.toString();
        art.layers.mask.open = mask.toString();

        for (Placement p : board.getPlacements()) {
            if (p.getSide() != Side.Top) {
                continue;
            }
            art.parts.add(part(p, byPlacement.get(p.getId())));
        }

        art.thumb.outline = new ArtPath(ArtPath.THUMB_DECIMALS).polygon(KGeom.simplify(outline, ArtPath.THUMB_MM,
                true)).toString();
        art.thumb.exposed = ArtPath.thumb(copper);
        art.thumb.mask = ArtPath.thumb(openings);
        art.stats.points = od.points() + cu.points() * 2 + mask.points();
        art.measure();
        return art;
    }

    /** The planner's pads with the board at the machine origin, where their centres are board coordinates. */
    static List<PadGeometry> pads(Board board, PadTypeResolver resolver) {
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
        PadTypeResolver r = resolver != null ? resolver : new PadTypeResolver(Collections.emptyList(), null);
        List<PadGeometry> out = new ArrayList<>();
        for (PadGeometry g : new BoardPadExtractor(r, true).extract(bl).pads) {
            if (g.wMm > 0 && g.hMm > 0 && Double.isFinite(g.xMm) && Double.isFinite(g.yMm)) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * {copper, mask} diameters of a fiducial: its first round footprint pad, else the package id
     * (Fiducial_1mm_Mask3mm), else 1.0 / 3.0 mm.
     */
    static double[] fiducialSize(Part part) {
        Package pkg = part == null ? null : part.getPackage();
        double copper = 0;
        Footprint fp = pkg == null ? null : pkg.getFootprint();
        if (fp != null && fp.getPads() != null) {
            double f = new Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
            for (Footprint.Pad pad : fp.getPads()) {
                if (pad.getRoundness() >= 100 && Math.abs(pad.getWidth() - pad.getHeight()) < 1e-9
                        && pad.getWidth() > 0) {
                    copper = pad.getWidth() * f;
                    break;
                }
            }
        }
        String id = pkg == null || pkg.getId() == null ? "" : pkg.getId();
        if (copper <= 0) {
            Matcher m = FID_SIZE.matcher(id);
            copper = m.find() ? Double.parseDouble(m.group(1)) : DEFAULT_FID_COPPER;
        }
        Matcher m = MASK_SIZE.matcher(id);
        double mask = m.find() ? Double.parseDouble(m.group(1)) : DEFAULT_FID_MASK;
        return new double[] {copper, Math.max(mask, copper)};
    }

    /** A part: the box of its pads grown 0.3 mm, in the part frame (+Y up). */
    static BoardArt.Part part(Placement p, List<PadGeometry> pads) {
        Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
        BoardArt.Part a = new BoardArt.Part();
        a.ref = p.getId();
        boolean fid = p.getType() == Placement.Type.Fiducial;
        a.kind = fid ? "fiducial" : p.isEnabled() ? "placement" : "pasteOnly";
        Part part = p.getPart();
        a.pkg = part == null || part.getPackage() == null ? null : part.getPackage().getId();
        a.value = null;
        a.x = KGeom.round6(l.getX());
        a.y = KGeom.round6(l.getY());
        a.a = KGeom.round6(l.getRotation());
        a.pads = pads == null ? 0 : pads.size();
        double[] box = KGeom.emptyBox();
        double t = Math.toRadians(-l.getRotation());
        double c = Math.cos(t);
        double s = Math.sin(t);
        if (pads != null) {
            for (PadGeometry g : pads) {
                for (double[] k : corners(g.xMm, g.yMm, g.wMm, g.hMm, g.rotationDeg)) {
                    double dx = k[0] - l.getX();
                    double dy = k[1] - l.getY();
                    KGeom.extend(box, dx * c - dy * s, dx * s + dy * c);
                }
            }
        }
        if (KGeom.isEmpty(box)) {
            double h = fid ? fiducialSize(part)[1] / 2 : 0.5;
            box = new double[] {-h, -h, h, h};
        }
        else {
            box = new double[] {box[0] - PART_MM, box[1] - PART_MM, box[2] + PART_MM, box[3] + PART_MM};
        }
        a.box = new double[] {BoardArtBuilder.r2(box[0]), BoardArtBuilder.r2(box[1]), BoardArtBuilder.r2(box[2]),
                BoardArtBuilder.r2(box[3])};
        return a;
    }

    /** The corners of a w × h rectangle centred on (x, y), turned by angleDeg. */
    static double[][] corners(double x, double y, double w, double h, double angleDeg) {
        double t = Math.toRadians(angleDeg);
        double c = Math.cos(t);
        double s = Math.sin(t);
        double[][] k = new double[4][];
        for (int i = 0; i < 4; i++) {
            double u = (i == 0 || i == 3 ? -1 : 1) * w / 2;
            double v = (i < 2 ? -1 : 1) * h / 2;
            k[i] = new double[] {x + u * c - v * s, y + u * s + v * c};
        }
        return k;
    }

    /** The convex hull of the points grown by d (each hull corner rounded with 16 points). */
    static double[] grownHull(double[] pts, double d) {
        double[] h = KGeom.convexHull(pts);
        KGeom.Pts q = new KGeom.Pts();
        for (int i = 0; i + 1 < h.length; i += 2) {
            for (int k = 0; k < 16; k++) {
                double t = 2 * Math.PI * k / 16;
                q.add(h[i] + d * Math.cos(t), h[i + 1] + d * Math.sin(t));
            }
        }
        return KGeom.convexHull(q.toArray());
    }
}
