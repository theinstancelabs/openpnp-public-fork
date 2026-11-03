package org.openpnp.instancelabs.dispense;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Map;

import org.openpnp.model.BoardLocation;

/** Register board: home if needed, find the fiducials, fit the board -> machine affine, probe, plan again. */
public final class RegisterRequest {
    /** auto = probe unless a same seat keeps its surface; always; never (registration only). */
    public enum ProbeMode {
        AUTO, ALWAYS, NEVER
    }

    /**
     * Plans the board again with a stored registration, or without one when it is null (the API's plan store);
     * returns the new plan id.
     */
    public interface Replanner {
        String replan(BoardRegistration reg) throws Exception;
    }

    /** The plan "Register board" was pressed on. */
    public DispensePlan plan;
    /** The board at its nominal seat, without our registration. */
    public BoardLocation nominal;
    public String boardKey;
    public BoardRegistration.Nominal nominalSeat;
    /** Inverse of the plan's own board -> machine transform (dots -> board mm). */
    public AffineTransform planToBoard;
    /** File board: the source z; job board: the board's global Z. */
    public double nominalZ;
    public ProbeMode probe = ProbeMode.AUTO;
    /**
     * Probe only (F10): the board has no fiducials, the plan carries no registration and the probe is ALWAYS. No
     * fiducial fit: N1 probes the board's targets where the plan puts the board, and the surface is tied to that seat.
     * Ignored for a board with fiducials (they are fitted, or too few are refused: its checks all stand).
     */
    public boolean probeOnly;
    public Replanner replanner;
    /**
     * From the board's KiCad drawing (BOARD_IMPORT_DESIGN.md §10.2 item 4), both null without one: fiducial name ->
     * {copper, mask} diameter in mm, and the circles N1 must not touch (holes, vias, outline and cut-outs; null:
     * a mounting hole is assumed in each corner, {@link ProbeTargets#forBoard}).
     */
    public Map<String, double[]> fiducialHints;
    public List<ProbeTargets.KeepOut> keepOuts;
}
