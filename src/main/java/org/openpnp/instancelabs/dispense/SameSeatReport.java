package org.openpnp.instancelabs.dispense;

import java.util.ArrayList;
import java.util.List;

/**
 * The same-seat check of Register board: 3 stored points of the tied board surface (the largest
 * triangle) touched again; the surface is kept only if all agree within {@link ZCheck#TOLERANCE_MM}.
 */
public class SameSeatReport {
    public static class Point {
        public int n;
        public double x, y;
        public Double bx, by;
        public double storedZ;
        /** The touch now, null without contact. */
        public Double z;
        public Double deltaMm;
        public boolean pass;
    }

    public List<Point> points = new ArrayList<>();
    public boolean pass;
    /** The stored points predated the last home and all passed: the Z check of that home is stamped too. */
    public boolean zCheckStamped;
    public String note;
}
