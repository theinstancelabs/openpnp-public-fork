package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** BR-98: the check on the fixture model, no machine (collision-model-design.md §3, §7 U1-U4). */
public class CollisionCheckTest {
    /** The fixture's values are of home 0; the SIM's home is later. */
    static final long HOME = 5L;

    static CollisionCheck.Leg leg(String label, double ax, double ay, double az, double bx, double by, double bz) {
        return new CollisionCheck.Leg(label, ax, ay, az, bx, by, bz);
    }

    static CollisionCheck.Result strict(BedModel m, CollisionCheck.Leg... legs) {
        return CollisionCheck.check(m, Arrays.asList(legs), CollisionCheck.Options.strict(HOME));
    }

    static CollisionCheck.Result contact(BedModel m, CollisionCheck.Leg... legs) {
        return CollisionCheck.check(m, Arrays.asList(legs), CollisionCheck.Options.contact(HOME));
    }

    /** A model with one fixed tool (a plate under the head) and one block on the bed. */
    static BedModel plateAndBlock() throws Exception {
        JsonObject j = BedFixture.json();
        j.add("tools", new JsonParser().parse("[{\"id\": \"probe\", \"moves\": \"fixed\", \"layers\": [{\"name\": \"plate\", "
                + "\"at\": \"head\", \"outline\": {\"box\": [-1, -1, 1, 1]}, \"bottomZ\": 5}]}]"));
        j.add("objects", new JsonParser().parse("[{\"id\": \"block\", \"name\": \"the block\", \"kind\": \"fixed\", "
                + "\"outline\": {\"box\": [10, -5, 20, 5]}, \"topZ\": 6, \"marginMm\": 1.0, \"touchable\": false, "
                + "\"measured\": {\"top\": {\"how\": \"n1-touch\", \"errMm\": 0.05}}, \"state\": \"measured\"}]"));
        return BedModel.parse(j);
    }

    /** U1: a box swept across a box: the exact shortfall; a miss by 0.1 mm (outside the margin) passes. */
    @Test
    public void theSweepFindsTheBlockBetweenTheEnds() throws Exception {
        BedModel m = plateAndBlock();
        CollisionCheck.Result r = strict(m, leg("X to the right", 0, 0, 31.5, 30, 0, 31.5));
        assertFalse(r.clear, "the plate crosses the block between its ends");
        assertEquals("block", r.hit.object.id);
        assertEquals("probe", r.hit.tool.id);
        assertEquals("plate", r.hit.layer.name);
        assertEquals("X to the right", r.hit.leg);
        // top 6 + Z margin 2 + the top's error 0.05 (a fixed part has no tip error), bottom 5
        assertEquals(3.05, r.hit.mm, 1e-9);
        assertEquals(5.0, r.hit.toolLowestZ, 1e-9);
        assertEquals(6.0, r.hit.blockerTopZ, 1e-9);
        // where it begins: the plate (head +-1) touches the block grown by its margin (x 9) with the head at x 8
        assertEquals(8.0, r.hit.atX, 1e-6);
        assertEquals(0.0, r.hit.atY, 1e-6);
        // the plate's edge 0.1 mm outside the block's margin: clear; 0.1 mm inside it: blocked
        assertTrue(strict(m, leg("X to the right", 0, -7.1, 31.5, 30, -7.1, 31.5)).clear);
        assertFalse(strict(m, leg("X to the right", 0, -6.9, 31.5, 30, -6.9, 31.5)).clear);
        // the start's X/Y error grows the block too
        CollisionCheck.Options o = CollisionCheck.Options.strict(HOME);
        o.xyErrMm = 0.2;
        assertFalse(CollisionCheck.check(m, Arrays.asList(leg("X", 0, -7.1, 31.5, 30, -7.1, 31.5)), o).clear);
    }

    /** U2: the lowest point of a leg is at one of its ends: N1 at the raw-falling end, the needle at the rising one. */
    @Test
    public void theLowestPointIsAtALegsEnd() throws Exception {
        JsonObject j = BedFixture.json();
        // a block under the needle's tip for head (124.178, 183.91): the needle at raw 50 is 17.865 above the bed there
        j.getAsJsonArray("objects").add(new JsonParser().parse("{\"id\": \"post\", \"name\": \"the post\", \"kind\": \"fixed\", "
                + "\"outline\": {\"circle\": [149.469, 107.984, 2]}, \"topZ\": 16, \"marginMm\": 1.0, \"touchable\": false, "
                + "\"measured\": {\"top\": {\"how\": \"caliper\", \"errMm\": 0.025}}, \"state\": \"measured\"}"));
        BedModel m = BedModel.parse(j);
        double h2 = 64.875 - BedFixture.rc(149.469, 107.984) - 50;
        CollisionCheck.Result r = strict(m, leg("Z up", 124.178, 183.91, 20, 124.178, 183.91, 50));
        assertFalse(r.clear);
        assertEquals("post", r.hit.object.id);
        assertEquals("needle", r.hit.tool.id);
        assertEquals(h2, r.hit.toolLowestZ, 1e-9, "the needle's lowest is at the raw-50 end");
        // top 16 + margin 2 + top error 0.025 + tip error 0.5 (see-saw, plane, K twice and the knees of another home)
        assertEquals(16 + 2 + 0.025 + 0.5 - h2, r.hit.mm, 1e-9);
        // the other way round the same: the order of the ends does not matter
        assertEquals(r.hit.mm, strict(m, leg("Z down", 124.178, 183.91, 50, 124.178, 183.91, 20)).hit.mm, 1e-12);
        // the same leg ending at raw 35 keeps both tips up: clear
        assertTrue(strict(m, leg("Z up", 124.178, 183.91, 20, 124.178, 183.91, 35)).clear);
    }

    /**
     * U3: contact (the passthrough) exempts only a tip layer, only on a measured touchable top that holds it, and only
     * {@code contactDepthMm} deep; any other layer reaching into that top is refused.
     */
    @Test
    public void onlyTheTipLayerIsExempt() throws Exception {
        BedModel m = BedFixture.model();
        // the needle's tip on the front blank (top 8.3) at 8.2: 0.1 mm deep, within contactDepthMm 0.25
        double hx = 110 - 25.291, hy = 160 + 75.926, r = 64.875 - BedFixture.rc(110, 160) - 8.2;
        CollisionCheck.Leg down = leg("dot", hx, hy, 31.5, hx, hy, r);
        assertTrue(contact(m, down).clear, "a dot on its own touchable top");
        CollisionCheck.Result s = strict(m, down);
        assertFalse(s.clear, "without the exemption it is a collision");
        assertEquals("blank-front", s.hit.object.id);
        // 0.3 mm deep: past contactDepthMm
        assertFalse(contact(m, leg("dot", hx, hy, 31.5, hx, hy, r + 0.2)).clear);
        // a layer just above the tip (at the head: not the tip layer) reaching into the same top is refused
        JsonObject j = BedFixture.json();
        j.getAsJsonArray("tools").get(1).getAsJsonObject().getAsJsonArray("layers").add(new JsonParser().parse(
                "{\"name\": \"collar\", \"at\": \"head\", \"outline\": {\"circle\": [25.291, -75.926, 1.5]}, "
                        + "\"aboveTipMm\": 0.5}"));
        CollisionCheck.Result c = contact(BedModel.parse(j), down);
        assertFalse(c.clear, "the collar is not exempt");
        assertEquals("collar", c.hit.layer.name);
        assertEquals("blank-front", c.hit.object.id);
        // never on a top that is not touchable: N1's tip on the holder's top
        double h1x = 20 + 24.178, h1y = 105 + 63.91, r1 = 14.0 + BedFixture.rc(20, 105);
        CollisionCheck.Result h = contact(m, leg("touch", h1x, h1y, 31.5, h1x, h1y, r1));
        assertFalse(h.clear);
        assertEquals("holder-left", h.hit.object.id);
        // nor where the tip is not inside the top's outline (it would graze the edge)
        double ex = 160.3 - 25.291;
        assertFalse(contact(m, leg("dot", ex, hy, 31.5, ex, hy, r)).clear);
    }

    /** The jog's exemption: the jogged tool's tip down to the jog floor over the probed (touchable) top under it. */
    @Test
    public void theJogExemptsItsToolsTipDownToTheFloor() throws Exception {
        BedModel m = BedFixture.model();
        double hx = 110 + 24.178, hy = 160 + 63.91, rcTip = BedFixture.rc(110, 160);
        double floorRaw = 8.3 + rcTip - 0.5; // the jog floor: 0.5 mm below the probed surface
        CollisionCheck.Leg down = leg("jog Z -2", hx, hy, floorRaw + 2, hx, hy, floorRaw);
        CollisionCheck.Options o = CollisionCheck.Options.strict(HOME);
        o.jogTool = "n1";
        o.jogFloorRaw = floorRaw;
        assertTrue(CollisionCheck.check(m, Arrays.asList(down), o).clear);
        assertFalse(strict(m, down).clear);
        o.jogTool = "needle";
        assertFalse(CollisionCheck.check(m, Arrays.asList(down), o).clear, "another tool's exemption");
        o.jogTool = "n1";
        o.jogFloorRaw = floorRaw + 0.2; // the leg goes below the floor: not exempt there
        assertFalse(CollisionCheck.check(m, Arrays.asList(down), o).clear);
    }

    /** Unmeasured objects block their outline at every height; the first leg with a shortfall is named. */
    @Test
    public void anUnmeasuredObjectBlocksAtEveryHeightAndTheFirstLegIsNamed() throws Exception {
        BedModel m = BedFixture.model();
        // the camera (40 above the bed) crosses the black blank (added, not measured) on the second leg
        CollisionCheck.Result r = strict(m, leg("Y to the back", 230, 100, 31.5, 230, 180, 31.5),
                leg("Y on", 230, 180, 31.5, 230, 400, 31.5), leg("X", 230, 400, 31.5, 380, 400, 31.5));
        assertFalse(r.clear);
        assertEquals("Y on", r.hit.leg);
        assertEquals(1, r.hit.legIndex);
        assertEquals("blank-black", r.hit.object.id);
        assertNull(r.hit.mm, "no shortfall: it blocks at every height");
        assertNull(r.hit.blockerTopZ);
    }

    /** The everyday moves must pass on the fixture: travel at the home's raw Z, N1 down to raw 0 at the homing corner. */
    @Test
    public void everydayMovesPass() throws Exception {
        BedModel m = BedFixture.model();
        // a loop round the bed at raw 31.5 over the holder and the tile (not the unmeasured blank)
        CollisionCheck.Result r = strict(m, leg("X", 0, 0, 31.5, 400, 0, 31.5), leg("Y", 400, 0, 31.5, 400, 480, 31.5),
                leg("X back", 400, 480, 31.5, 0, 480, 31.5), leg("Y front", 0, 480, 31.5, 0, 0, 31.5));
        assertTrue(r.clear, () -> "travel: " + r.hit);
        assertTrue(r.hit.mm > 10, "the closest is far below: " + r.hit);
        assertTrue(r.hit.vertical);
        // G28's Z at the homing corner: N1 to its end stop raw 0 hits nothing
        CollisionCheck.Leg corner = leg("Z to raw 0", 0, 0, 31.5, 0, 0, 0);
        assertTrue(strict(m, corner).clear, () -> "homing corner: " + strict(m, corner).hit);
        // and still with the bed reaching under it: N1 at raw 0 is 3.09 above the plane there (needs 2 + 0.3)
        JsonObject j = BedFixture.json();
        j.getAsJsonObject("bed").add("outline", new JsonParser().parse("{\"box\": [-40, -80, 450, 490]}"));
        CollisionCheck.Result w = strict(BedModel.parse(j), corner);
        assertTrue(w.clear, () -> "bed under the corner: " + w.hit);
        assertEquals(0 - BedFixture.rc(-24.178, -63.91) - 2.3, w.hit.mm, 1e-9, "N1's tip over the bed is the closest");
        assertEquals("bed", w.hit.object.id);
    }

    /** U4: a 5,000-leg program (dots on the front blank) is proven in under 100 ms. */
    @Test
    public void fiveThousandLegsUnderAHundredMs() throws Exception {
        BedModel m = BedFixture.model();
        List<CollisionCheck.Leg> legs = new ArrayList<>();
        // the needle's raw 8.19..8.22 above the bed over the dots (the plane there is raw -2.994..-2.962), 1 mm above it
        double dot = 64.875 + 2.97 - 8.2, hop = dot - 1;
        double px = 70 - 25.291, py = 130 + 75.926;
        for (int i = 0; i < 1250; i++) {
            double x = 70 + (i % 20) * 4 - 25.291, y = 130 + (i / 20 % 14) * 4 + 75.926;
            legs.add(leg("hop", px, py, hop, x, y, hop));
            legs.add(leg("down", x, y, hop, x, y, dot));
            legs.add(leg("up", x, y, dot, x, y, hop));
            legs.add(leg("settle", x, y, hop, x, y, hop));
            px = x;
            py = y;
        }
        assertEquals(5000, legs.size());
        CollisionCheck.Options o = CollisionCheck.Options.contact(HOME);
        CollisionCheck.Result r = null;
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 6; run++) {
            long t0 = System.nanoTime();
            r = CollisionCheck.check(m, legs, o);
            best = Math.min(best, System.nanoTime() - t0);
        }
        CollisionCheck.Result last = r;
        assertTrue(last.clear, () -> "the program is clear: " + last.hit);
        assertTrue(best < 100_000_000L, "best of 6: " + best / 1e6 + " ms");
    }
}
