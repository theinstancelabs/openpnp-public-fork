package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Z after a home: references (board tied, blank, anchor X/Y) measured before the home, the 0.03 mm
 * verdict, and the I4 rule that refuses needle work on Z data older than the last home until a Z check
 * for that home passed on a point in their Z frame (measured under the same home).
 */
public class ZCheckTest {
    static final long ANCHOR_AT = 1_793_200_000_000L;
    static final long TOUCH_OFF_AT = ANCHOR_AT + 3 * 60_000;
    static final long BOARD_AT = ANCHOR_AT + 38 * 60_000;
    static final long BLANK_AT = ANCHOR_AT + 30 * 60_000;
    static final long HOME_AT = ANCHOR_AT + 77 * 60_000;
    /** The home all the fixture's Z data were measured under (their Z frame). */
    static final long FRAME_AT = ANCHOR_AT - 10 * 60_000;

    static DispenseConfig cfg() {
        DispenseConfig c = ZFrameTest.cfg();
        c.needleAnchor.setAtMs = ANCHOR_AT;
        c.needleAnchor.homeAtMs = FRAME_AT;
        c.needleZOffsetSetAtMs = TOUCH_OFF_AT;
        c.needleZOffsetHomeAtMs = FRAME_AT;
        c.surfaces.get("board").setAtMs = BOARD_AT;
        c.surfaces.get("board").homeAtMs = FRAME_AT;
        DispenseConfig.SurfaceMap blank = new DispenseConfig.SurfaceMap();
        blank.points = ZFrameTest.points(new double[][] {{322.0, 225.5, 5.62}, {360.0, 225.5, 5.60},
            {340.0, 240.0, 5.61}}, 0);
        blank.setAtMs = BLANK_AT;
        blank.homeAtMs = FRAME_AT;
        c.surfaces.put("blank", blank);
        // the purge row on the blank: a board run's scope uses the blank surface too
        c.purgeRow.x0Mm = 330.0;
        c.purgeRow.y0Mm = 230.0;
        c.purgeRow.spots = 10;
        return c;
    }

    static InterlockReport check(DispenseConfig c, Long home, ZCheck.Result z, boolean zCheckFirst) {
        DispensePlan p = ZFrameTest.plan(c, 5.5);
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = p;
        in.estimatedPressurisedSeconds = 10;
        in.scope = DispenseService.boardScope(c, p);
        in.lastHomeAtMs = home;
        in.zCheck = z;
        in.zCheckFirst = zCheckFirst;
        return Interlocks.check(in);
    }

    @Test
    public void referencesInOrderAndOnlyBeforeTheHome() {
        DispenseConfig c = cfg();
        List<ZCheck.Reference> l = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        assertEquals(5 + 3 + 1, l.size());
        assertEquals("board", l.get(0).name);
        assertEquals(303.848, l.get(0).x, 1e-9);
        assertEquals(5.425, l.get(0).z, 1e-9);
        assertEquals(Long.valueOf(BOARD_AT), l.get(0).atMs);
        assertEquals("blank", l.get(5).name);
        assertEquals(322.0, l.get(5).x, 1e-9);
        assertEquals(ZCheck.NEEDLE_FRAME, l.get(8).name);
        assertEquals(333.1, l.get(8).x, 1e-9);
        assertEquals(5.5, l.get(8).z, 1e-9);
        // a board surface tied to another registration is no reference
        l = ZCheck.references(c, "another-seat", HOME_AT);
        assertEquals("blank", l.get(0).name);
        assertEquals(4, l.size());
        // a surface probed after the home is in the new frame already
        c.surfaces.get("blank").setAtMs = HOME_AT + 1;
        l = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        assertEquals(6, l.size());
        assertEquals(ZCheck.NEEDLE_FRAME, l.get(5).name);
        // the anchor without X/Y has no spot to touch
        c.needleAnchor.xMm = null;
        c.surfaces.get("board").setAtMs = HOME_AT + 1;
        assertTrue(ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).isEmpty());
        assertEquals("Z must be checked after the home at " + ZCheck.clock(HOME_AT) + ", but no point measured before "
                + "it is left to touch (no blank or board surface, no needle-frame X/Y): record the needle frame again",
                ZCheck.noReferenceMessage(HOME_AT));
    }

    @Test
    public void verdictAtThreeHundredthsOfAMillimetre() {
        ZCheck.Reference r = new ZCheck.Reference();
        r.z = 5.620;
        assertTrue(ZCheck.verdict(r, 5.620 + 0.029));
        assertTrue(ZCheck.verdict(r, 5.620 - 0.029));
        assertFalse(ZCheck.verdict(r, 5.620 + 0.031));
        assertFalse(ZCheck.verdict(r, 5.620 - 0.031));
        ZCheck.Result res = ZCheck.result(r, 5.651, HOME_AT + 10, HOME_AT);
        assertFalse(res.pass);
        assertEquals(0.031, res.deltaMm, 1e-9);
    }

    @Test
    public void noRuleWithoutAKnownHome() {
        assertTrue(check(cfg(), null, null, false).ok(), check(cfg(), null, null, false).toString());
        assertTrue(ZCheck.stale(cfg(), new Interlocks.Scope(), null).isEmpty());
    }

    @Test
    public void staleDataAreNamedWithTheirTimes() {
        InterlockReport r = check(cfg(), HOME_AT, null, false);
        List<String> i4 = ZFrameTest.i4(r);
        assertEquals(1, i4.size(), i4.toString());
        String t = ZCheck.clock(HOME_AT);
        assertEquals("the needle frame (" + ZCheck.clock(ANCHOR_AT) + "), the touch-off (" + ZCheck.clock(TOUCH_OFF_AT)
                + "), the 'board' surface (" + ZCheck.clock(BOARD_AT) + ") and the 'blank' surface ("
                + ZCheck.clock(BLANK_AT) + ") were measured before the home at " + t + ", and Z has not been checked "
                + "since: Check Z (one N1 touch, \u2248 20 s); Run does it by itself", i4.get(0));
        // data measured after the home are not stale
        DispenseConfig c = cfg();
        c.needleAnchor.setAtMs = HOME_AT + 1;
        c.needleZOffsetSetAtMs = HOME_AT + 2;
        c.surfaces.get("board").setAtMs = HOME_AT + 3;
        c.surfaces.get("blank").setAtMs = HOME_AT + 3;
        assertTrue(check(c, HOME_AT, null, false).ok());
        c.surfaces.get("blank").setAtMs = BLANK_AT;
        assertTrue(ZFrameTest.i4(check(c, HOME_AT, null, false)).get(0)
                .startsWith("the 'blank' surface (" + ZCheck.clock(BLANK_AT) + ") was measured before the home"));
        assertTrue(ZCheck.clock(HOME_AT).matches("\\d\\d:\\d\\d"));
        assertTrue(ZCheck.dayClock(HOME_AT).matches("\\d\\d-\\d\\d \\d\\d:\\d\\d"));
    }

    @Test
    public void onlyAPassingCheckForThisHomeAccepts() {
        DispenseConfig c = cfg();
        ZCheck.Reference ref = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).get(5); // the blank's first point
        ZCheck.Result pass = ZCheck.vouch(ZCheck.result(ref, 5.63, HOME_AT + 60_000, HOME_AT), ref, c, null);
        assertTrue(pass.pass);
        assertEquals(4, pass.covers.size(), "one frame: the blank vouches for all four data");
        assertTrue(check(c, HOME_AT, pass, false).ok());
        // the same check after a later home does not count
        assertFalse(check(c, HOME_AT + 3_600_000, pass, false).ok());
        // a failed check for this home: "Z moved"
        ZCheck.Result moved = ZCheck.result(ref, 4.92, HOME_AT + 60_000, HOME_AT);
        assertFalse(moved.pass);
        List<String> i4 = ZFrameTest.i4(check(c, HOME_AT, moved, false));
        assertEquals(1, i4.size());
        assertEquals("Z moved -0.70 mm at the home of " + ZCheck.clock(HOME_AT) + ": N1 touched the blank at X 322.00, "
                + "Y 225.50 at Z 4.920, where it touched 5.620 at " + ZCheck.clock(BLANK_AT) + ". The needle frame, the "
                + "touch-off and the surfaces are off by this (the needle by about twice), and so are the places. Record "
                + "the needle frame again with both tips (Setup \u203a Needle Z frame), probe the blank, take the "
                + "places' Z again with N1 (Setup \u203a Places), touch off and register the board again.",
                i4.get(0));
        // the failed check stands even when the task would check Z itself
        assertEquals(i4, ZFrameTest.i4(check(c, HOME_AT, moved, true)));
    }

    @Test
    public void aTaskThatChecksZItselfGetsANote() {
        InterlockReport r = check(cfg(), HOME_AT, null, true);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.contains("I4: Z data predate the home at " + ZCheck.clock(HOME_AT)
                + ": checks Z first (one N1 touch)"), r.notes.toString());
        // a run that will home makes every Z datum stale: it checks Z after its home
        Interlocks.Inputs in = new Interlocks.Inputs();
        DispenseConfig c = cfg();
        DispensePlan p = ZFrameTest.plan(c, 5.5);
        in.cfg = c;
        in.machineEnabled = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = new ArrayList<>();
        in.plan = p;
        in.scope = DispenseService.boardScope(c, p);
        in.homeFirst = true;
        in.zCheckFirst = true;
        r = Interlocks.check(in);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.contains("I4: homes first, then checks Z (one N1 touch)"), r.notes.toString());
        assertTrue(r.notes.contains("MACHINE: not homed: homes first"), r.notes.toString());
    }

    @Test
    public void scopesWithoutNeedleMotionHaveNoRule() {
        Interlocks.Inputs in = new Interlocks.Inputs();
        in.cfg = cfg();
        in.machineEnabled = true;
        in.machineHomed = true;
        in.compatibleNozzleTips = new ArrayList<>();
        in.scope = Interlocks.Scope.noNeedle();
        in.lastHomeAtMs = HOME_AT;
        assertTrue(Interlocks.check(in).ok());
        // shots at the waste: the needle frame and the touch-off only
        in.scope = DispenseService.shotsScope("waste", true);
        List<String> i4 = ZFrameTest.i4(Interlocks.check(in));
        assertEquals(1, i4.size());
        assertTrue(i4.get(0).startsWith("the needle frame (" + ZCheck.clock(ANCHOR_AT) + ") and the touch-off ("
                + ZCheck.clock(TOUCH_OFF_AT) + ") were measured before the home"), i4.get(0));
    }

    static Interlocks.Scope boardScope(DispenseConfig c) {
        return DispenseService.boardScope(c, ZFrameTest.plan(c, 5.5));
    }

    static List<String> whats(List<ZCheck.Stale> l) {
        List<String> w = new ArrayList<>();
        for (ZCheck.Stale d : l) {
            w.add(d.what);
        }
        return w;
    }

    /**
     * Review 1 (machine safety): the needle frame, the touch-off and the blank were measured in frame F0;
     * home 1 moved Z 0.8 mm (the bench's frame vs OpenPnP's); Register board probed the board after it;
     * home 2 repeats home 1. The board point agrees after home 2, but it says nothing about F0: the
     * needle would be off by about 2 x 0.8 mm.
     */
    @Test
    public void aPointMeasuredAfterAnEarlierHomeDoesNotVouchForOlderData() {
        DispenseConfig c = cfg();
        long home1 = BLANK_AT + 60_000;
        c.surfaces.get("board").homeAtMs = home1; // probed at BOARD_AT, after home 1
        List<ZCheck.Reference> refs = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        ZCheck.Reference board = refs.get(0);
        assertEquals("board", board.name);
        assertEquals(Long.valueOf(home1), board.homeAtMs);
        ZCheck.Result pass = ZCheck.vouch(ZCheck.result(board, board.z + 0.025, HOME_AT + 60_000, HOME_AT), board,
                c, null);
        assertTrue(pass.pass);
        assertEquals(Collections.singletonList("the 'board' surface"), whats(pass.covers));
        // I4 still refuses the needle work, naming the data of the older frame
        List<String> i4 = ZFrameTest.i4(check(c, HOME_AT, pass, false));
        assertEquals(1, i4.size(), i4.toString());
        assertTrue(i4.get(0).startsWith("the needle frame (" + ZCheck.clock(ANCHOR_AT) + "), the touch-off ("
                + ZCheck.clock(TOUCH_OFF_AT) + ") and the 'blank' surface (" + ZCheck.clock(BLANK_AT)
                + ") were measured before the home at " + ZCheck.clock(HOME_AT)), i4.get(0));
        // so the check goes on to a point of their frame, the blank, and finds the move
        List<ZCheck.Stale> open = ZCheck.unchecked(pass, HOME_AT, ZCheck.stale(c, boardScope(c), HOME_AT));
        assertEquals(3, open.size(), whats(open).toString());
        assertTrue(ZCheck.unreachable(refs, open).isEmpty());
        ZCheck.Reference blank = ZCheck.first(refs, open);
        assertEquals("blank", blank.name);
        ZCheck.Result moved = ZCheck.vouch(ZCheck.result(blank, blank.z + 0.80, HOME_AT + 90_000, HOME_AT), blank, c,
                pass);
        assertFalse(moved.pass);
        assertTrue(moved.covers.isEmpty());
        i4 = ZFrameTest.i4(check(c, HOME_AT, moved, true));
        assertTrue(i4.get(0).startsWith("Z moved 0.80 mm at the home of "), i4.toString());
        // had the blank agreed, the two touches together vouch for all of it
        ZCheck.Result both = ZCheck.vouch(ZCheck.result(blank, blank.z, HOME_AT + 90_000, HOME_AT), blank, c, pass);
        assertEquals(4, both.covers.size(), whats(both.covers).toString());
        assertTrue(check(c, HOME_AT, both, false).ok());
        assertTrue(ZCheck.unchecked(both, HOME_AT, open).isEmpty());
        // a pass for an earlier home vouches for nothing now
        assertEquals(open, ZCheck.unchecked(both, HOME_AT + 1, open));
        // nothing of F0 left to touch (no blank, no needle-frame X/Y): refused before anything moves
        c.surfaces.remove("blank");
        c.needleAnchor.xMm = null;
        refs = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        open = ZCheck.unchecked(pass, HOME_AT, ZCheck.stale(c, boardScope(c), HOME_AT));
        List<ZCheck.Stale> none = ZCheck.unreachable(refs, open);
        assertEquals(java.util.Arrays.asList("the needle frame", "the touch-off"), whats(none));
        assertEquals(null, ZCheck.first(refs, none));
        assertEquals("Z must be checked after the home at " + ZCheck.clock(HOME_AT) + ", but no point measured under "
                + "the same home as the needle frame (" + ZCheck.clock(ANCHOR_AT) + ") and the touch-off ("
                + ZCheck.clock(TOUCH_OFF_AT) + ") is left to touch, and a home in between may have moved Z: record "
                + "the needle frame again and touch off", ZCheck.noFrameReferenceMessage(HOME_AT, none));
    }

    /** Data saved before frames were recorded: their frame is unknown, so a point vouches only for its own datum. */
    @Test
    public void dataOfUnknownFrameVouchOnlyForThemselves() {
        DispenseConfig c = cfg();
        c.needleAnchor.homeAtMs = null;
        c.needleZOffsetHomeAtMs = null;
        c.surfaces.get("board").homeAtMs = null;
        c.surfaces.get("blank").homeAtMs = null;
        List<ZCheck.Reference> refs = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        ZCheck.Reference board = refs.get(0);
        ZCheck.Result pass = ZCheck.vouch(ZCheck.result(board, board.z, HOME_AT + 60_000, HOME_AT), board, c, null);
        assertEquals(Collections.singletonList("the 'board' surface"), whats(pass.covers));
        List<ZCheck.Stale> open = ZCheck.unchecked(pass, HOME_AT, ZCheck.stale(c, boardScope(c), HOME_AT));
        assertEquals(java.util.Arrays.asList("the needle frame", "the touch-off", "the 'blank' surface"), whats(open));
        // the needle frame has its own spot; the touch-off has none
        assertEquals(Collections.singletonList("the touch-off"), whats(ZCheck.unreachable(refs, open)));
        ZCheck.Reference spot = refs.get(refs.size() - 1);
        assertEquals(ZCheck.NEEDLE_FRAME, spot.name);
        ZCheck.Result anchor = ZCheck.vouch(ZCheck.result(spot, spot.z, HOME_AT + 90_000, HOME_AT), spot, c, pass);
        assertEquals(java.util.Arrays.asList("the 'board' surface", "the needle frame"), whats(anchor.covers));
    }

    /**
     * The touches a check makes (what Run and Check Z announce): one per Z frame of the open data, none when a datum
     * has no point of its frame left.
     */
    @Test
    public void oneTouchPerZFrame() {
        DispenseConfig c = cfg();
        List<ZCheck.Stale> open = ZCheck.stale(c, boardScope(c), HOME_AT);
        assertEquals(1, ZCheck.touches(ZCheck.references(c, ZFrameTest.SEAT, HOME_AT), open), "one frame");
        // the board probed under a later home than the needle frame, the touch-off and the blank: two frames
        c.surfaces.get("board").homeAtMs = BLANK_AT + 60_000;
        open = ZCheck.stale(c, boardScope(c), HOME_AT);
        assertEquals(2, ZCheck.touches(ZCheck.references(c, ZFrameTest.SEAT, HOME_AT), open));
        // without the blank and the anchor X/Y nothing in the needle frame's home is left to touch
        c.surfaces.remove("blank");
        c.needleAnchor.xMm = null;
        open = ZCheck.stale(c, boardScope(c), HOME_AT);
        assertEquals(0, ZCheck.touches(ZCheck.references(c, ZFrameTest.SEAT, HOME_AT), open));
    }

    /** A pass moves what it vouched for to the frame of its home: after the next home one touch is enough again. */
    @Test
    public void aPassMovesTheDataItVouchedForToItsHome() {
        DispenseConfig c = cfg();
        c.surfaces.get("board").homeAtMs = BLANK_AT + 60_000;
        List<ZCheck.Reference> refs = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT);
        ZCheck.Reference board = refs.get(0), blank = refs.get(5);
        ZCheck.Result first = ZCheck.vouch(ZCheck.result(board, board.z, HOME_AT + 60_000, HOME_AT), board, c, null);
        assertTrue(ZCheck.restamp(c, first));
        assertEquals(Long.valueOf(HOME_AT), c.surfaces.get("board").homeAtMs);
        assertEquals(Long.valueOf(FRAME_AT), c.needleAnchor.homeAtMs, "not vouched for: its frame stays");
        ZCheck.Result both = ZCheck.vouch(ZCheck.result(blank, blank.z, HOME_AT + 90_000, HOME_AT), blank, c, first);
        assertTrue(ZCheck.restamp(c, both));
        assertFalse(ZCheck.restamp(c, both), "nothing left to move");
        assertEquals(Long.valueOf(HOME_AT), c.needleAnchor.homeAtMs);
        assertEquals(Long.valueOf(HOME_AT), c.needleZOffsetHomeAtMs);
        assertEquals(Long.valueOf(HOME_AT), c.surfaces.get("blank").homeAtMs);
        long home3 = HOME_AT + 3_600_000;
        refs = ZCheck.references(c, ZFrameTest.SEAT, home3);
        ZCheck.Result next = ZCheck.vouch(ZCheck.result(refs.get(0), refs.get(0).z, home3 + 60_000, home3),
                refs.get(0), c, both);
        assertEquals(4, next.covers.size(), "one touch, one frame");
        assertTrue(check(c, home3, next, false).ok());
        // a failed check moves nothing
        ZCheck.Result moved = ZCheck.result(refs.get(0), refs.get(0).z + 0.5, home3 + 60_000, home3);
        assertFalse(ZCheck.restamp(c, ZCheck.vouch(moved, refs.get(0), c, null)));
    }

    /**
     * Review 1 (machine safety): the blank probed again after a home keeps the points it replaced while they are a
     * reference of an older Z frame, listed after its own points, so a check of the needle frame and the touch-off
     * (the anchor has no X/Y here) still has a point; a later probe under the same home carries them on.
     */
    @Test
    public void theBlankProbedAgainAfterAHomeKeepsTheReferenceOfItsFrame() {
        DispenseConfig c = cfg();
        c.needleAnchor.xMm = null;
        DispenseConfig.SurfaceMap old = c.surfaces.get("blank");
        DispenseConfig.SurfaceMap probed = new DispenseConfig.SurfaceMap();
        probed.points = ZFrameTest.points(new double[][] {{325.0, 228.0, 5.62}, {358.0, 228.0, 5.60},
            {340.0, 238.0, 5.61}}, 0);
        probed.setAtMs = HOME_AT + 60_000;
        probed.homeAtMs = HOME_AT;
        probed.replaced = ZCheck.kept(old, HOME_AT);
        assertEquals(old.points.size(), probed.replaced.points.size());
        assertEquals(Long.valueOf(BLANK_AT), probed.replaced.setAtMs);
        assertEquals(Long.valueOf(FRAME_AT), probed.replaced.homeAtMs);
        c.surfaces.put("blank", probed);
        List<ZCheck.Reference> refs = ZCheck.references(c, "another-seat", HOME_AT);
        assertEquals(3, refs.size(), "the kept points only: the new ones were measured after the home");
        assertEquals("blank", refs.get(0).name);
        assertEquals(322.0, refs.get(0).x, 1e-9);
        assertEquals(5.62, refs.get(0).z, 1e-9);
        List<ZCheck.Stale> open = ZCheck.stale(c, DispenseService.blankScope(c, "blank", false, false, null), HOME_AT);
        assertEquals(java.util.Arrays.asList("the needle frame", "the touch-off"), whats(open));
        assertEquals(refs.get(0), ZCheck.first(refs, open));
        assertEquals(1, ZCheck.touches(refs, open), "one touch vouches for both");
        // the kept points are no datum of their own: a pass there vouches for the data of their frame only
        ZCheck.Result pass = ZCheck.vouch(ZCheck.result(refs.get(0), 5.63, HOME_AT + 90_000, HOME_AT), refs.get(0), c,
                null);
        assertEquals(java.util.Arrays.asList("the needle frame", "the touch-off", "the 'board' surface"),
                whats(pass.covers));
        // probed again under the same home: the kept points carry on; under no known home, or of an unknown frame,
        // nothing is kept
        assertEquals(probed.replaced, ZCheck.kept(probed, HOME_AT));
        assertEquals(null, ZCheck.kept(old, null));
        old.homeAtMs = null;
        assertEquals(null, ZCheck.kept(old, HOME_AT));
    }

    /**
     * rc1 review (machine safety): a place carries the time it was set, no Z frame a touch vouches for. After a failed
     * check of this home, a place the run dispenses at (prime, the test dot, shots, the touch-off's prime at the waste)
     * that was set before that home is refused until its Z is taken again; a passing check, a check of an earlier home,
     * or a place set after the home leave it alone.
     */
    @Test
    public void afterZMovedAPlaceSetBeforeTheHomeIsRefusedWhereTheRunDispensesAtIt() {
        DispenseConfig c = cfg();
        c.locations.put("waste", new DispenseConfig.NamedLocation(300, 230, 5.5)); // saved without a time
        DispenseConfig.NamedLocation cup = new DispenseConfig.NamedLocation(150, 40, 5.0);
        cup.setAtMs = BLANK_AT;
        c.locations.put("cup", cup);
        ZCheck.Reference ref = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).get(5); // the blank's first point
        ZCheck.Result moved = ZCheck.result(ref, 4.92, HOME_AT + 60_000, HOME_AT);
        ZCheck.Result passed = ZCheck.result(ref, 5.62, HOME_AT + 60_000, HOME_AT);
        assertEquals(java.util.Arrays.asList("waste", "cup"), ZCheck.movedPlaces(c, moved, HOME_AT));
        assertEquals(Collections.emptyList(), ZCheck.movedPlaces(c, passed, HOME_AT));
        assertEquals(Collections.emptyList(), ZCheck.movedPlaces(c, moved, HOME_AT + 3_600_000), "an earlier home's");
        // the needle frame and the touch-off measured again under this home: only the place is left
        c.needleAnchor.setAtMs = HOME_AT + 120_000;
        c.needleAnchor.homeAtMs = HOME_AT;
        c.needleZOffsetSetAtMs = HOME_AT + 240_000;
        c.needleZOffsetHomeAtMs = HOME_AT;
        List<String> i4 = ZFrameTest.i4(ZFrameTest.check(c, null, DispenseService.shotsScope("cup", false), HOME_AT,
                moved));
        assertEquals(Collections.singletonList("place 'cup' Z 5.000 was taken at " + ZCheck.clock(BLANK_AT) + ", before "
                + "the home at " + ZCheck.clock(HOME_AT) + " where Z moved -0.70 mm: take its Z again with N1 touching "
                + "(Setup \u203a Places)"), i4);
        i4 = ZFrameTest.i4(ZFrameTest.check(c, null, DispenseService.blankScope(c, "blank", true, true, null), HOME_AT,
                moved));
        assertTrue(i4.size() == 2 && i4.get(0).startsWith("Z moved -0.70 mm") && i4.get(1).startsWith("place 'waste' "
                + "Z 5.500 was taken at an unknown time, before the home at "), "the touch-off primes at the waste: "
                        + i4);
        assertTrue(ZFrameTest.check(c, null, DispenseService.shotsScope("cup", false), HOME_AT, passed).ok());
        assertTrue(ZFrameTest.check(c, null, DispenseService.shotsScope("cup", false), null, null).ok());
        // taken again after the home
        cup.setAtMs = HOME_AT + 300_000;
        assertTrue(ZFrameTest.check(c, null, DispenseService.shotsScope("cup", false), HOME_AT, moved).ok());
        assertEquals(Collections.singletonList("waste"), ZCheck.movedPlaces(c, moved, HOME_AT));
    }

    /**
     * Review 1 (machine safety), the way out of "Z moved" without another home: the needle frame recorded again
     * after the home set the offset again, so it replaces the touch-off (which I4 then asks for, and which may run);
     * the blank and the board, not measured again, still refuse until they are; after that a run passes.
     */
    @Test
    public void aNeedleFrameRecordedAfterTheHomeReplacesTheTouchOff() {
        DispenseConfig c = cfg();
        ZCheck.Reference ref = ZCheck.references(c, ZFrameTest.SEAT, HOME_AT).get(5); // the blank's first point
        ZCheck.Result moved = ZCheck.result(ref, 4.92, HOME_AT + 60_000, HOME_AT);
        assertFalse(moved.pass);
        // before: the touch-off is a stale datum of the touch-off run, and Z moved
        Interlocks.Scope touchOff = DispenseService.blankScope(c, "blank", false, true, null);
        assertEquals(java.util.Arrays.asList("the needle frame", "the touch-off", "the 'blank' surface"),
                whats(ZCheck.stale(c, touchOff, HOME_AT)));
        // the needle frame recorded again under this home (both contacts 0.70 mm lower)
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5 - 0.70, 2.91 - 0.70, null, null, HOME_AT + 120_000);
        c.needleAnchor.homeAtMs = HOME_AT;
        c.needleZOffsetMm = c.needleAnchor.offsetMm();
        assertFalse(c.isNeedleTouchedOff());
        assertTrue(ZCheck.touchOffReplaced(c));
        assertEquals(Collections.singletonList("the 'blank' surface"), whats(ZCheck.stale(c, touchOff, HOME_AT)),
                "the touch-off the frame replaced is no Z datum any more");
        // the blank, not probed again, still refuses the touch-off: its Z moved
        List<String> i4 = ZFrameTest.i4(ZFrameTest.check(c, null, touchOff, HOME_AT, moved));
        assertEquals(1, i4.size(), i4.toString());
        assertTrue(i4.get(0).startsWith("Z moved -0.70 mm"), i4.toString());
        // probed again after the home: the touch-off runs
        DispenseConfig.SurfaceMap blank = c.surfaces.get("blank");
        blank.points = ZFrameTest.points(new double[][] {{322.0, 225.5, 5.62}, {360.0, 225.5, 5.60},
            {340.0, 240.0, 5.61}}, -0.70);
        blank.setAtMs = HOME_AT + 180_000;
        blank.homeAtMs = HOME_AT;
        assertEquals(Collections.emptyList(), ZFrameTest.i4(ZFrameTest.check(c, null, touchOff, HOME_AT, moved)));
        // touched off: a board run still needs the board, not measured again since Z moved
        c.needleZOffsetSetAtMs = HOME_AT + 240_000;
        c.needleZOffsetHomeAtMs = HOME_AT;
        assertTrue(c.isNeedleTouchedOff());
        assertFalse(ZCheck.touchOffReplaced(c));
        i4 = ZFrameTest.i4(check(c, HOME_AT, moved, false));
        assertEquals(1, i4.size(), i4.toString());
        assertTrue(i4.get(0).startsWith("Z moved -0.70 mm"), i4.toString());
        // registered (probed) again: the run passes I4
        DispenseConfig.SurfaceMap board = c.surfaces.get("board");
        board.points = ZFrameTest.points(ZFrameTest.ZMAP, -0.70);
        board.setAtMs = HOME_AT + 300_000;
        board.homeAtMs = HOME_AT;
        InterlockReport run = check(c, HOME_AT, moved, false);
        assertTrue(run.ok(), run.toString());
    }
}
