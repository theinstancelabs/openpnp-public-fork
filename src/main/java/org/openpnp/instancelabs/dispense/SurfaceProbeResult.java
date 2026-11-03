package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/** Outcome of an automatic surface probe (Gson-friendly). */
public final class SurfaceProbeResult {
    public RunResult run;
    public boolean ok;
    public String message;
    /** home | probe | save, when ABORTED. */
    public String stoppedStage;
    public HomeReport home;
    public String surfaceName;
    public VacuumProber.Window window;
    public List<VacuumProber.Point> points = new ArrayList<>();
    public PlaneReport plane;
    public boolean saved;
}
