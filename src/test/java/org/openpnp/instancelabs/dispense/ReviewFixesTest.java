package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Board;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Placement;

/**
 * Review findings on the bench-recipe engine: the clean-pad box follows the pad size, and a board
 * surface is tied to the board's registration only (not to its placements or nominal Z).
 */
public class ReviewFixesTest {
    static DispensePlan.PlannedPad pad(double w, double h, double rot, int dots) {
        DispensePlan.PlannedPad p = new DispensePlan.PlannedPad();
        p.padId = "P1";
        p.wMm = w;
        p.hMm = h;
        p.rotationDeg = rot;
        p.dotCount = dots;
        return p;
    }

    @Test
    public void cleanBoxIsTheBenchBoxOn0603AndShrinksOnSmallerPads() {
        DispenseConfig.CleanPadCheck s = new DispenseConfig.CleanPadCheck();
        // 0603 pad 0.80 x 0.95: the bench's 0.806 x 0.807 box, at 0 and 90 deg (bench parity)
        for (double rot : new double[] {0, 90, 180, -90}) {
            DispenseConfig.CleanPadCheck b = DispenseService.cleanBox(s, pad(0.80, 0.95, rot, 1));
            assertEquals(0.806, b.boxWMm, 1e-9, "rot " + rot);
            assertEquals(0.807, b.boxHMm, 1e-9, "rot " + rot);
        }
        // 0402 pad 0.54 x 0.64: 0.05 mm inside each edge, swapped at 90 deg
        DispenseConfig.CleanPadCheck b = DispenseService.cleanBox(s, pad(0.54, 0.64, 0, 1));
        assertEquals(0.44, b.boxWMm, 1e-9);
        assertEquals(0.54, b.boxHMm, 1e-9);
        b = DispenseService.cleanBox(s, pad(0.54, 0.64, 90, 1));
        assertEquals(0.54, b.boxWMm, 1e-9);
        assertEquals(0.44, b.boxHMm, 1e-9);
        // QFN perimeter pad 0.80 x 0.25: the narrow side never goes below 0.15 mm
        b = DispenseService.cleanBox(s, pad(0.80, 0.25, 0, 1));
        assertEquals(0.806, b.boxWMm, 1e-9);
        assertEquals(0.15, b.boxHMm, 1e-9);
        // 45 deg: the square that fits inside, 0.54 / (cos + sin) - 0.10
        b = DispenseService.cleanBox(s, pad(0.54, 0.64, 45, 1));
        assertEquals(0.54 / Math.sqrt(2) - 0.10, b.boxWMm, 1e-9);
        assertEquals(b.boxWMm, b.boxHMm, 1e-12);
        // a 2-dot pad 3.1 x 3.1 (grid 2 x 1): each cell 1.55 x 3.1 holds the full box
        b = DispenseService.cleanBox(s, pad(3.1, 3.1, 0, 2));
        assertEquals(0.806, b.boxWMm, 1e-9);
        // unknown pad: the configured box
        b = DispenseService.cleanBox(s, null);
        assertEquals(0.806, b.boxWMm, 1e-9);
        assertEquals(s.grayLevel, b.grayLevel);
        assertEquals(s.minBrightFraction, b.minBrightFraction, 0);
    }

    static BoardLocation board(double x, double y, double z, double rot) {
        Board board = new Board();
        board.setName("reg");
        Placement p = new Placement("R1");
        p.setLocation(new Location(LengthUnit.Millimeters, 10, 10, 0, 0));
        p.setType(Placement.Type.Placement);
        board.addPlacement(p);
        BoardLocation bl = new BoardLocation(board);
        bl.setLocation(new Location(LengthUnit.Millimeters, x, y, z, rot));
        return bl;
    }

    @Test
    public void boardRegistrationIgnoresPlacementsAndNominalZ() {
        BoardLocation bl = board(295.56, 114.81, 5.43, -1.783);
        String reg = DispenseService.boardRegistration(bl);
        String full = DispenseService.boardFingerprint(bl);
        // a placement edit or a corrected board Z: the plan is stale, the board has not moved
        bl.getBoard().getPlacements().get(0).setEnabled(false);
        bl.setLocation(new Location(LengthUnit.Millimeters, 295.56, 114.81, 5.50, -1.783));
        assertEquals(reg, DispenseService.boardRegistration(bl));
        assertNotEquals(full, DispenseService.boardFingerprint(bl));
        // a re-seat (new X/Y or rotation): a new registration, the probes must be taken again
        bl.setLocation(new Location(LengthUnit.Millimeters, 295.96, 114.41, 5.50, -1.013));
        assertNotEquals(reg, DispenseService.boardRegistration(bl));
    }
}
