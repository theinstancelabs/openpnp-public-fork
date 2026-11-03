package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/** The probe part of Register board (Gson-friendly). */
public class ProbeReport {
    /** auto | always | never */
    public String mode;
    public boolean needed;
    /** Why it ran (or did not): "no surface tied to this registration", "re-seated", ... */
    public String why;
    public boolean skipped;
    public VacuumProber.Window window;
    public List<VacuumProber.Point> points = new ArrayList<>();
    public PlaneReport plane;
    public boolean saved;
    public Long surfaceSetAtMs;
}
