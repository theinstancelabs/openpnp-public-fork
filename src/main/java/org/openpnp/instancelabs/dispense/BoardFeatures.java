package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * What Register board needs to know about a board, in board millimetres: its outline, its fiducials
 * (top side, enabled) and its pads, so the probe can keep N1 off copper features and fiducial masks.
 * Pure: the pads come from {@link BoardPadExtractor} on the board placed at the origin, where its
 * "global" pad centres are the board coordinates.
 */
public final class BoardFeatures {
    /** Fiducial copper and mask diameters when neither a footprint nor the package name tells them. */
    public static final double DEFAULT_FIDUCIAL_MM = 1.0;
    public static final double DEFAULT_MASK_MM = 3.0;
    private static final Pattern FIDUCIAL_MM = Pattern.compile("(?i)fiducial[_-]?(\\d+(?:\\.\\d+)?)mm");
    private static final Pattern MASK_MM = Pattern.compile("(?i)mask(\\d+(?:\\.\\d+)?)mm");

    public static final class Fiducial {
        public String name;
        public double x, y, diameterMm, maskDiameterMm;
    }

    public static final class Pad {
        public String id;
        public double x, y, wMm, hMm, rotationDeg;
        /** False without a pad type or geometry: kept out as a circle instead of its rectangle. */
        public boolean known;
    }

    public String boardName;
    /** The board file's name (the key of {@link DispenseConfig.VacuumProbe#boardSpots}), or null. */
    public String fileName;
    /** Board outline from the board's dimensions, 0 when unknown. */
    public double widthMm, heightMm;
    public final List<Fiducial> fiducials = new ArrayList<>();
    public final List<Pad> pads = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public static BoardFeatures of(Board board, PadTypeResolver resolver) {
        return of(board, resolver, null);
    }

    /**
     * The features, with the fiducial sizes of the board's drawing where it has them.
     *
     * @param fiducialHints fiducial name -> {copper, mask} diameter in mm from the board's KiCad drawing
     *        (BOARD_IMPORT_DESIGN.md §10.2 item 4); null, or a name it lacks: from the footprint or the package
     *        name ({@link #fiducialSize})
     */
    public static BoardFeatures of(Board board, PadTypeResolver resolver, Map<String, double[]> fiducialHints) {
        BoardFeatures f = new BoardFeatures();
        f.boardName = board.getName();
        f.fileName = board.getFile() == null ? null : board.getFile().getName();
        Location dim = board.getDimensions() == null ? null : board.getDimensions().convertToUnits(LengthUnit.Millimeters);
        f.widthMm = dim == null || !(dim.getX() > 0) ? 0 : dim.getX();
        f.heightMm = dim == null || !(dim.getY() > 0) ? 0 : dim.getY();
        for (Placement p : board.getPlacements()) {
            if (p.getType() != Placement.Type.Fiducial || p.getSide() != Side.Top || !p.isEnabled()) {
                continue;
            }
            Location l = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            Fiducial fd = new Fiducial();
            fd.name = p.getId();
            fd.x = l.getX();
            fd.y = l.getY();
            double[] hint = fiducialHints == null ? null : fiducialHints.get(p.getId());
            double[] d = hint != null && hint.length >= 2 && hint[0] > 0 ? new double[] {hint[0], Math.max(hint[1],
                    hint[0])} : fiducialSize(p.getPart());
            fd.diameterMm = d[0];
            fd.maskDiameterMm = d[1];
            f.fiducials.add(fd);
        }
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, 0, 0, 0, 0));
        BoardPadExtractor.Result r = new BoardPadExtractor(resolver, true).extract(bl);
        f.warnings.addAll(r.warnings);
        for (PadGeometry g : r.pads) {
            Pad pad = new Pad();
            pad.id = g.padId;
            pad.x = g.xMm;
            pad.y = g.yMm;
            pad.wMm = g.wMm;
            pad.hMm = g.hMm;
            pad.rotationDeg = g.rotationDeg;
            pad.known = g.wMm > 0 && g.hMm > 0;
            f.pads.add(pad);
        }
        return f;
    }

    /**
     * {copper, mask} diameter of a fiducial part: the first round footprint pad (roundness 100, w ==
     * h), else parsed from the package id ("Fiducial_1mm_Mask3mm"), else 1.0 / 3.0 mm.
     */
    static double[] fiducialSize(Part part) {
        Package pkg = part == null ? null : part.getPackage();
        double d = Double.NaN;
        Footprint fp = pkg == null ? null : pkg.getFootprint();
        if (fp != null && fp.getPads() != null) {
            double unit = new Length(1, fp.getUnits()).convertToUnits(LengthUnit.Millimeters).getValue();
            for (Footprint.Pad pad : fp.getPads()) {
                if (pad.getRoundness() >= 100 && pad.getWidth() > 0 && Math.abs(pad.getWidth() - pad.getHeight()) < 1e-9) {
                    d = pad.getWidth() * unit;
                    break;
                }
            }
        }
        String id = pkg == null || pkg.getId() == null ? "" : pkg.getId();
        if (Double.isNaN(d)) {
            Matcher m = FIDUCIAL_MM.matcher(id);
            d = m.find() ? Double.parseDouble(m.group(1)) : DEFAULT_FIDUCIAL_MM;
        }
        Matcher m = MASK_MM.matcher(id);
        double mask = m.find() ? Double.parseDouble(m.group(1)) : DEFAULT_MASK_MM;
        return new double[] {d, Math.max(mask, d)};
    }

    /** True when the outline is known. */
    public boolean hasOutline() {
        return widthMm > 0 && heightMm > 0;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "board %s %.1f x %.1f mm: %d fiducials, %d pads", boardName, widthMm,
                heightMm, fiducials.size(), pads.size());
    }
}
