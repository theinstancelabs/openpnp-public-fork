package org.openpnp.instancelabs.dispense;

/** A fitted surface plane z = a + b x + c y as a result shows it (Gson-friendly). */
public class PlaneReport {
    public double a, b, c;
    public double maxResidualMm, slopeMmPerMm, minZ, maxZ;
    /** Blank probe: whether every purge spot (and the blank place) lies within the margin of the points. */
    public Boolean coversSpots;
    /** Blank probe: the purge spots checked for coverage. */
    public Integer spots;

    public static PlaneReport of(SurfacePlane p) {
        PlaneReport r = new PlaneReport();
        r.a = p.a;
        r.b = p.b;
        r.c = p.c;
        r.maxResidualMm = p.maxAbsResidualMm;
        r.slopeMmPerMm = p.slope();
        r.minZ = p.minZ;
        r.maxZ = p.maxZ;
        return r;
    }
}
