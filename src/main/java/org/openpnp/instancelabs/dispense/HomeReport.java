package org.openpnp.instancelabs.dispense;

/** Whether a task homed first, why, and what it did before the home (Gson-friendly). */
public class HomeReport {
    public boolean needed;
    /** {@link HomeTracker.Plan#whyCode} */
    public String whyCode;
    public String why;
    public boolean done;
    /** Seconds the home took, when it ran. */
    public Double seconds;
    /** N1's Z when a STOP had left it low and it was raised (Z only) to travel Z before the home. */
    public Double n1RaisedFromZ;

    /** The report of a home plan (before anything ran). */
    public static HomeReport of(HomeTracker.Plan p) {
        HomeReport r = new HomeReport();
        r.needed = p.needed;
        r.whyCode = p.whyCode;
        r.why = p.why;
        return r;
    }
}
