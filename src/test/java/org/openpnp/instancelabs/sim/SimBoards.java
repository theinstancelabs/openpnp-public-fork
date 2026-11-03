package org.openpnp.instancelabs.sim;

import java.util.Collections;

import org.openpnp.instancelabs.dispense.PadTypeResolver;
import org.openpnp.model.Board;
import org.openpnp.model.Footprint;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Package;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;

/** Boards built in code for the sim tests: an ftp-like board, and a KiCad-like one with footprint fiducials. */
final class SimBoards {
    /** ftp.board.xml's fiducials (tools/register.py:14). */
    static final double[][] FTP_FIDUCIALS = {{62.4465812, 4.69103014}, {12.5, 70.0}, {62.5, 70.0}};
    static final double PAD_W = 0.75, PAD_H = 0.9, PAD_X = 0.775;

    private SimBoards() {
    }

    static Placement placement(String id, Part part, double x, double y, double rot, Placement.Type type) {
        Placement p = new Placement(id);
        p.setPart(part);
        p.setLocation(new Location(LengthUnit.Millimeters, x, y, 0, rot));
        p.setType(type);
        return p;
    }

    /** Two 0.75 × 0.9 mm footprint pads at ±0.775 mm. */
    static Part chip() {
        Package pkg = new Package("CHIP_0603");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        for (int i = 0; i < 2; i++) {
            Footprint.Pad pad = new Footprint.Pad();
            pad.setName(Integer.toString(i + 1));
            pad.setX(i == 0 ? -PAD_X : PAD_X);
            pad.setY(0);
            pad.setWidth(PAD_W);
            pad.setHeight(PAD_H);
            fp.addPad(pad);
        }
        pkg.setFootprint(fp);
        Part part = new Part("CHIP");
        part.setPackage(pkg);
        return part;
    }

    /** 75 × 75 mm, FID1-FID3 of package Fiducial_1mm_Mask3mm (no footprint pads), R1 at (37.5, 37.5), R2 at (20, 50) turned 90°. */
    static Board ftpLike() {
        Package fidPkg = new Package("Fiducial_1mm_Mask3mm");
        Part fid = new Part("Fiducial_1mm_Mask3mm-Fiducial");
        fid.setPackage(fidPkg);
        Board b = new Board();
        b.setName("ftp-like");
        b.setDimensions(new Location(LengthUnit.Millimeters, 75, 75, 0, 0));
        for (int i = 0; i < 3; i++) {
            b.addPlacement(placement("FID" + (i + 1), fid, FTP_FIDUCIALS[i][0], FTP_FIDUCIALS[i][1], 0,
                    Placement.Type.Fiducial));
        }
        Part chip = chip();
        b.addPlacement(placement("R1", chip, 37.5, 37.5, 0, Placement.Type.Placement));
        b.addPlacement(placement("R2", chip, 20, 50, 90, Placement.Type.Placement));
        return b;
    }

    /** 40 × 30 mm; fiducials of a package without a size in its name and a 1.2 mm round footprint pad (as KiCad's). */
    static Board kicadLike() {
        Package fidPkg = new Package("Fiducial_Round");
        Footprint fp = new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        Footprint.Pad pad = new Footprint.Pad();
        pad.setName("1");
        pad.setWidth(1.2);
        pad.setHeight(1.2);
        pad.setRoundness(100);
        fp.addPad(pad);
        fidPkg.setFootprint(fp);
        Part fid = new Part("Fiducial_Round-FID");
        fid.setPackage(fidPkg);
        Board b = new Board();
        b.setName("kicad-like");
        b.setDimensions(new Location(LengthUnit.Millimeters, 40, 30, 0, 0));
        b.addPlacement(placement("FID_A", fid, 3, 3, 0, Placement.Type.Fiducial));
        b.addPlacement(placement("FID_B", fid, 37, 27, 0, Placement.Type.Fiducial));
        Placement off = placement("FID_C", fid, 37, 3, 0, Placement.Type.Fiducial);
        off.setEnabled(false);
        b.addPlacement(off);
        b.addPlacement(placement("C1", chip(), 20, 15, 45, Placement.Type.Placement));
        return b;
    }

    static PadTypeResolver footprintsOnly() {
        return new PadTypeResolver(Collections.emptyList(), null);
    }
}
