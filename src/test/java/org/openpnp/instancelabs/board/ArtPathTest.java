package org.openpnp.instancelabs.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The art's path data (design §4.1, §6.3): rounding, −0, implicit L, H/V, arcs, and reading it back. */
public class ArtPathTest {
    @Test
    public void numbersAreRoundedToTheArtsPrecision() {
        assertEquals("1.23", ArtPath.format(1.234, 2));
        assertEquals("12", ArtPath.format(12.0, 2));
        assertEquals("12", ArtPath.format(11.999, 2));
        assertEquals("0.5", ArtPath.format(0.5, 2));
        assertEquals("-3.1", ArtPath.format(-3.1, 2));
        assertEquals("0.07", ArtPath.format(0.07, 2));
        assertEquals("-0.07", ArtPath.format(-0.07, 2));
        assertEquals("2147", ArtPath.format(2147.0, 2));
        assertEquals("0.1", ArtPath.format(0.12, 1));
        assertEquals(1.23, ArtPath.round(1.2349, 2), 0);
    }

    @Test
    public void minusZeroIsZero() {
        assertEquals("0", ArtPath.format(-0.0, 2));
        assertEquals("0", ArtPath.format(-0.004, 2));
        assertEquals("M0 0", new ArtPath().moveTo(-0.001, -0.0).toString());
        assertEquals("0.0", Double.toString(ArtPath.round(-0.001, 2)));
    }

    @Test
    public void linesUseHvAndAnImplicitL() {
        ArtPath p = new ArtPath().moveTo(0, 0).lineTo(1, 2).lineTo(3, 4).lineTo(5, 4).lineTo(5, 6).lineTo(7, 8);
        assertEquals("M0 0 1 2 3 4H5V6L7 8", p.toString());
        assertEquals(6, p.points());
    }

    @Test
    public void repeatedPointsAreLeftOut() {
        ArtPath p = new ArtPath().moveTo(1, 1).lineTo(1.001, 1.002).lineTo(2, 2);
        assertEquals("M1 1 2 2", p.toString());
        assertEquals(2, p.points());
    }

    @Test
    public void polygonsCloseWithZAndDropAClosingRepeat() {
        String d = new ArtPath().polygon(new double[] {0, 0, 75, 0, 75, 75, 0, 75, 0.001, 0}).toString();
        assertEquals("M0 0H75V75H0Z", d);
        // a sliver that rounds to fewer than 3 points is not drawn
        assertEquals("", new ArtPath().polygon(new double[] {0, 0, 0.001, 0, 0.002, 0.001}).toString());
        // two shapes: the second starts with M
        assertEquals("M0 0H1V1ZM2 2H3V3Z", new ArtPath().polygon(new double[] {0, 0, 1, 0, 1, 1})
                .polygon(new double[] {2, 2, 3, 2, 3, 3}).toString());
    }

    @Test
    public void polylinesStayOpen() {
        assertEquals("M0 0 1 1M2 2H3", new ArtPath().polyline(new double[] {0, 0, 1, 1})
                .polyline(new double[] {2, 2, 3, 2}).toString());
    }

    @Test
    public void arcsAreWrittenAsA() {
        ArtPath p = new ArtPath().circle(1, 2, 0.5);
        assertEquals("M1.5 2A0.5 0.5 0 1 1 0.5 2A0.5 0.5 0 1 1 1.5 2Z", p.toString());
        // a rounded rectangle: four sides and four counter-clockwise corners
        String r = new ArtPath().roundRect(0, 0, 2, 1, 0.25, 0).toString();
        assertEquals("M1 -0.25V0.25A0.25 0.25 0 0 1 0.75 0.5H-0.75A0.25 0.25 0 0 1 -1 0.25V-0.25"
                + "A0.25 0.25 0 0 1 -0.75 -0.5H0.75A0.25 0.25 0 0 1 1 -0.25Z", r);
        // square corners: four points, no arcs
        assertEquals("M1 -0.5V0.5H-1V-0.5Z", new ArtPath().roundRect(0, 0, 2, 1, 0, 0).toString());
        // turned 90°: the long side runs along Y
        assertEquals("M0.5 1H-0.5V-1H0.5Z", new ArtPath().roundRect(0, 0, 2, 1, 0, 90).toString());
    }

    @Test
    public void pathsAreReadBack() {
        String d = "M0 0H75V75H0ZM10 10 20 10 20 20L10 20ZM1.5 2A0.5 0.5 0 1 1 0.5 2A0.5 0.5 0 1 1 1.5 2ZM5 5 6 6";
        List<ArtPath.Sub> subs = ArtPath.parse(d);
        assertEquals(4, subs.size());
        assertArrayEquals(new double[] {0, 0, 75, 0, 75, 75, 0, 75}, subs.get(0).pts, 0);
        assertTrue(subs.get(0).closed);
        assertArrayEquals(new double[] {10, 10, 20, 10, 20, 20, 10, 20}, subs.get(1).pts, 0);
        assertArrayEquals(new double[] {1.5, 2, 0.5, 2, 1.5, 2}, subs.get(2).pts, 0);
        assertFalse(subs.get(3).closed);
        assertThrows(IllegalArgumentException.class, () -> ArtPath.parse("M0 0Q1 1 2 2"));
        assertThrows(IllegalArgumentException.class, () -> ArtPath.parse("0 0 1 1"));
        assertThrows(IllegalArgumentException.class, () -> ArtPath.parse("M0"));
    }

    @Test
    public void writingAndReadingAgree() {
        double[] loop = {0.004, 0, 10.126, 0, 10.126, 5.556, 3.333, 7.777};
        List<ArtPath.Sub> subs = ArtPath.parse(new ArtPath().polygon(loop).toString());
        assertArrayEquals(new double[] {0, 0, 10.13, 0, 10.13, 5.56, 3.33, 7.78}, subs.get(0).pts, 1e-9);
    }
}
