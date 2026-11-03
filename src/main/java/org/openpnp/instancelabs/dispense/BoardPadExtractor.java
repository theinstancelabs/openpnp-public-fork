package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.model.Abstract2DLocatable.Side;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.util.Utils2D;

/**
 * OpenPnP Board + BoardLocation -> pads in global machine coordinates. Uses OpenPnP's own
 * board-to-machine transform (Utils2D.calculateBoardPlacementLocation), which applies the
 * fiducial-corrected local-to-global transform when a fiducial check has set one.
 */
public class BoardPadExtractor {
    public static class Result {
        public final List<PadGeometry> pads = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
    }

    private final PadTypeResolver resolver;
    private final boolean includeDisabled;

    public BoardPadExtractor(PadTypeResolver resolver, boolean includeDisabled) {
        this.resolver = resolver;
        this.includeDisabled = includeDisabled;
    }

    public Result extract(BoardLocation bl) {
        Result res = new Result();
        if (bl.getGlobalSide() != Side.Top) {
            throw new IllegalArgumentException(
                    "Board " + bl.getId() + " is placed bottom-up; bottom-side dispensing is not supported");
        }
        for (Placement p : bl.getBoard().getPlacements()) {
            if (p.getType() != Placement.Type.Placement) {
                continue;
            }
            if (!includeDisabled && !p.isEnabled()) {
                continue;
            }
            if (p.getSide() != Side.Top) {
                res.warnings.add(p.getId() + ": bottom-side placement skipped");
                continue;
            }
            Part part = p.getPart();
            Package pkg = part == null ? null : part.getPackage();
            if (pkg == null) {
                res.warnings.add(p.getId() + ": no part/package, skipped");
                continue;
            }
            Location pl = p.getLocation().convertToUnits(LengthUnit.Millimeters);
            Footprint fp = pkg.getFootprint();
            List<double[]> local = new ArrayList<>(); // x, y, rot, w, h, roundness
            List<String> names = new ArrayList<>();
            List<PadTypeResolver.Resolution> types = new ArrayList<>();
            PadGeometry.Source source;
            if (fp != null && fp.getPads() != null && !fp.getPads().isEmpty()) {
                source = PadGeometry.Source.FOOTPRINT;
                double f = new org.openpnp.model.Length(1, fp.getUnits())
                        .convertToUnits(LengthUnit.Millimeters).getValue();
                for (Footprint.Pad fpad : fp.getPads()) {
                    double w = fpad.getWidth() * f;
                    double h = fpad.getHeight() * f;
                    double x = fpad.getX() * f;
                    double y = fpad.getY() * f;
                    local.add(new double[] {x, y, fpad.getRotation(), w, h, fpad.getRoundness()});
                    names.add(fpad.getName());
                    types.add(resolver.explain(pkg.getId(), fpad.getName(), x, y,
                            DotMath.padAreaMm2(w, h, fpad.getRoundness())));
                }
            }
            else {
                source = PadGeometry.Source.PAD_TYPE;
                PadTypeResolver.Resolution how = resolver.explain(pkg.getId(), Double.NaN, Double.NaN, Double.NaN);
                PadType t = how.type;
                if (t == null) {
                    PadGeometry g = new PadGeometry();
                    g.padId = p.getId();
                    g.placementId = p.getId();
                    g.packageId = pkg.getId();
                    describe(g, part, how);
                    Location gl = Utils2D.calculateBoardPlacementLocation(bl, pl);
                    g.xMm = gl.getX();
                    g.yMm = gl.getY();
                    g.rotationDeg = gl.getRotation();
                    g.surfaceZMm = gl.getZ();
                    g.source = source;
                    res.pads.add(g); // planner flags NO_PAD_TYPE
                    continue;
                }
                List<double[]> lay = layout(t);
                if (lay == null) {
                    PadGeometry g = new PadGeometry();
                    g.padId = p.getId();
                    g.placementId = p.getId();
                    g.packageId = pkg.getId();
                    g.padType = t;
                    describe(g, part, how);
                    Location gl = Utils2D.calculateBoardPlacementLocation(bl, pl);
                    g.xMm = gl.getX();
                    g.yMm = gl.getY();
                    g.surfaceZMm = gl.getZ();
                    g.source = source;
                    res.pads.add(g); // planner flags NO_GEOMETRY
                    continue;
                }
                int i = 1;
                for (double[] l : lay) {
                    // the layout's size; a mapping for some pads of the package may give a pad another type
                    String name = Integer.toString(i++);
                    PadTypeResolver.Resolution own = resolver.explain(pkg.getId(), name, Double.NaN, Double.NaN,
                            Double.NaN);
                    local.add(new double[] {l[0], l[1], 0, t.padWMm, t.padHMm, t.padRoundness});
                    names.add(name);
                    types.add(PadTypeResolver.Resolution.PAD_MAP.equals(own.rule) ? own : how);
                }
            }
            double rad = Math.toRadians(pl.getRotation());
            double c = Math.cos(rad), s = Math.sin(rad);
            for (int i = 0; i < local.size(); i++) {
                double[] l = local.get(i);
                // pad centre in board coordinates
                double bx = pl.getX() + l[0] * c - l[1] * s;
                double by = pl.getY() + l[0] * s + l[1] * c;
                Location boardFrame = new Location(LengthUnit.Millimeters, bx, by, pl.getZ(),
                        pl.getRotation() + l[2]);
                Location gl = Utils2D.calculateBoardPlacementLocation(bl, boardFrame)
                        .convertToUnits(LengthUnit.Millimeters);
                PadGeometry g = new PadGeometry();
                g.placementId = p.getId();
                g.padName = names.get(i);
                g.padId = p.getId() + "." + g.padName;
                g.packageId = pkg.getId();
                g.xMm = gl.getX();
                g.yMm = gl.getY();
                g.rotationDeg = gl.getRotation();
                g.wMm = l[3];
                g.hMm = l[4];
                g.roundness = l[5];
                g.surfaceZMm = gl.getZ();
                g.source = source;
                g.padType = types.get(i).type;
                describe(g, part, types.get(i));
                res.pads.add(g);
            }
        }
        return res;
    }

    /** The part id and the rule that chose the pad type (the pad inspector). */
    private static void describe(PadGeometry g, Part part, PadTypeResolver.Resolution how) {
        g.partId = part.getId();
        g.typeRule = how.rule;
        g.typeRuleDetail = how.detail;
        g.typeRuleMatched = how.matched;
    }

    /** Pad centres in the part frame for a pad type, or null for footprint-only types. */
    static List<double[]> layout(PadType t) {
        List<double[]> l = new ArrayList<>();
        switch (t.layout) {
            case SINGLE:
                l.add(new double[] {0, 0});
                return l;
            case TWO_TERMINAL:
                l.add(new double[] {-t.pitchMm / 2, 0});
                l.add(new double[] {t.pitchMm / 2, 0});
                return l;
            case SOT23_3:
                l.add(new double[] {-t.pitchMm / 2, -0.95});
                l.add(new double[] {-t.pitchMm / 2, 0.95});
                l.add(new double[] {t.pitchMm / 2, 0});
                return l;
            default:
                return null;
        }
    }
}
