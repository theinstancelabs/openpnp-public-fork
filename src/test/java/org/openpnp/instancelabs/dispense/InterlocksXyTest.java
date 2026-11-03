package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;

/**
 * Tip setups (§22.3, §22.8): the landing rule (while where the needle lands is not checked only prime, the test dot
 * and the landing check dispense), what makes a landing stale (a needle change, N2's head offset changed), Keep where
 * it lands (straight setups with a landing of their own only), and the I4 offset rule for a frame close to N1's.
 */
public class InterlocksXyTest {
    static final String PUT_ON = "and where it lands has not been checked: check where it lands (Setup › Dispenser & "
            + "tip).";

    static List<String> i4(InterlockReport r) {
        return r.violations.stream().filter(v -> v.code.equals("I4")).map(v -> v.message).collect(Collectors.toList());
    }

    static Interlocks.Inputs unchecked(Interlocks.Scope scope) {
        Interlocks.Inputs in = InterlocksTest.ok();
        in.xyChecked = false;
        in.scope = scope;
        return in;
    }

    @Test
    public void whileTheLandingIsNotCheckedOnlyTheWasteAndTheLandingCheckDispense() {
        // a board run
        Interlocks.Inputs board = InterlocksTest.ok();
        board.xyChecked = false;
        List<String> v = i4(Interlocks.check(board));
        assertEquals(1, v.size(), v.toString());
        assertEquals("the " + board.cfg.tip.name + " was put on at " + ZCheck.clock(board.cfg.needleChangedAtMs) + " "
                + PUT_ON, v.get(0));
        // a pad calibration's ladder, the old dot ladder and the touch-off on the blank: refused
        DispenseConfig c = board.cfg;
        for (Interlocks.Scope sc : new Interlocks.Scope[] {DispenseService.blankScope(c, "blank", false, false, null),
            DispenseService.blankScope(c, "blank", true, false, null),
            DispenseService.blankScope(c, "blank", false, true, null), DispenseService.shotsScope("cup", false)}) {
            Interlocks.Inputs in = unchecked(sc);
            in.plan = null;
            in.boardRun = false;
            in.purgeDots = 0;
            assertTrue(i4(Interlocks.check(in)).stream().anyMatch(x -> x.endsWith(PUT_ON)),
                    Interlocks.check(in).toString());
        }
        // prime and the test dot at the waste, and the landing check itself: allowed
        Interlocks.Scope landing = DispenseService.blankScope(c, "blank", false, false, null);
        landing.landingWaiver = Interlocks.Scope.LANDING_CHECK;
        for (Interlocks.Scope sc : new Interlocks.Scope[] {DispenseService.shotsScope("waste", true),
            DispenseService.shotsScope(DispenseService.WASTE_TEST_DOT, true), landing}) {
            Interlocks.Inputs in = unchecked(sc);
            in.plan = null;
            in.boardRun = false;
            in.purgeDots = 0;
            InterlockReport r = Interlocks.check(in);
            assertTrue(i4(r).stream().noneMatch(x -> x.endsWith(PUT_ON)), r.toString());
            assertTrue(r.notes.stream().anyMatch(n -> n.contains("before the landing is checked")), r.notes.toString());
        }
        // the landing checked: the board runs
        board.xyChecked = true;
        assertTrue(Interlocks.check(board).ok(), Interlocks.check(board).toString());
    }

    @Test
    public void theOffsetRuleForAFrameCloseToN1s() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.zFrameToleranceMm = 1.0;
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 4.0, null, null, 1500L); // -1.5 mm
        assertEquals("The " + c.tip.name + "'s tip is 1.50 mm from N1's: I4 cannot tell a height taken with the needle; "
                + "lower zFrameToleranceMm below 0.70 mm (Settings).", Interlocks.frameTooClose(c));
        Interlocks.Inputs in = InterlocksTest.ok();
        in.cfg.zFrameToleranceMm = 1.0;
        in.cfg.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 4.0, null, null, 1500L);
        in.cfg.needleZOffsetMm = -1.5;
        assertTrue(i4(Interlocks.check(in)).contains(Interlocks.frameTooClose(in.cfg)), i4(Interlocks.check(in))
                .toString());
        c.zFrameToleranceMm = 0.6; // 2 x 0.6 + 0.1 = 1.3 < 1.5
        assertNull(Interlocks.frameTooClose(c));
        // the 22G's -2.59 mm frame is far enough
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, null, null, 1500L);
        c.zFrameToleranceMm = 1.0;
        assertNull(Interlocks.frameTooClose(c));
        // the board Z limit at planning: min(2.0, o - 0.5)
        assertNull(DispenseService.boardZProblem(c, 5.5 + 1.9));
        assertNotNull(DispenseService.boardZProblem(c, 5.5 + 2.1));
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 4.0, null, null, 1500L);
        assertNull(DispenseService.boardZProblem(c, 5.5 + 0.9));
        String p = DispenseService.boardZProblem(c, 5.5 + 1.1);
        assertNotNull(p, "1.1 mm off is refused with a 1.5 mm frame (limit 1.0)");
        assertTrue(p.contains("(at most 1.0 mm"), p);
    }

    @Test
    public void aNeedleChangeAndAnotherHeadOffsetStaleTheLandingAndKeepIsForStraightTipsOnly() throws Exception {
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            assertTrue(svc.isNeedleXyChecked(), "the landing kept from before the update");
            // the 22G is bent: keep is refused
            assertEquals("A bent tip lands where its bend points: check where it lands.", svc.keepLandingProblem());
            svc.recordNeedleChange("test", null);
            assertFalse(svc.isNeedleXyChecked());
            EngineRefusal k = assertThrows(EngineRefusal.class, () -> svc.keepLanding(svc.getConfig().needleChangedAtMs));
            assertEquals("A bent tip lands where its bend points: check where it lands.", k.getMessage());
            // the 25G straight has no landing of its own on its first mount
            TipSetup s25 = f.add25();
            f.mount(s25.key);
            assertEquals("The 25G straight has no landing of its own yet: check where it lands.",
                    svc.keepLandingProblem());
            f.frame(-2.59);
            assertFalse(svc.isNeedleXyChecked());
            DispenseConfig.NeedleLanding l = f.land(2.4, -0.4);
            assertNotNull(l);
            assertTrue(svc.isNeedleXyChecked());
            assertEquals(0, svc.getConfig().needleXyTrim.dxMm, 0, "the trim is reset");
            // a needle change of the same straight tip: kept with two presses
            svc.recordNeedleChange("test", null);
            assertFalse(svc.isNeedleXyChecked());
            assertNull(svc.keepLandingProblem());
            DispenseConfig.NeedleLanding kept = svc.keepLanding(svc.getConfig().needleChangedAtMs);
            assertEquals(2.4, kept.dxMm, 0);
            assertTrue(kept.note.startsWith("kept after the needle change at "), kept.note);
            assertTrue(svc.isNeedleXyChecked());
            f.frame(-2.59);
            // someone changes N2's head offset in OpenPnP: every landing set under the old one is stale
            Location ho = f.sim.n2.getHeadOffsets();
            f.sim.n2.setHeadOffsets(ho.add(new Location(LengthUnit.Millimeters, 0.5, 0, 0, 0)));
            assertFalse(svc.isNeedleXyChecked());
            assertEquals("N2's head offset changed in OpenPnP since the 25G straight's landing was checked: set it back, "
                    + "or check where it lands", svc.needleXyProblem());
            assertEquals("N2's head offset changed since the 25G straight's landing was checked: check where it lands.",
                    svc.keepLandingProblem());
            DispensePlan p = svc.newPlanner().plan("toy", new java.util.ArrayList<>(), 100, 50);
            assertTrue(svc.checkInterlocks(p, 0).violations.stream().anyMatch(v -> v.message.startsWith(
                    "N2's head offset changed")), svc.checkInterlocks(p, 0).toString());
            f.sim.n2.setHeadOffsets(ho);
            assertTrue(svc.isNeedleXyChecked(), "set back");
            // a trim set under another head offset is stale too
            svc.setXyTrim(0.1, 0, "fine", svc.getConfig().needleChangedAtMs);
            assertTrue(svc.isNeedleXyChecked());
            f.sim.n2.setHeadOffsets(ho.add(new Location(LengthUnit.Millimeters, 0, 0.3, 0, 0)));
            assertFalse(svc.isNeedleXyChecked());
            f.sim.n2.setHeadOffsets(ho);
        }
    }

    @Test
    public void aLandingCheckedAfterTheClockWasSetBackStillCounts() throws Exception {
        // the needle token can lie ahead of the clock (the clock set back after the change, or two changes in one
        // millisecond): a landing measured after the change is stamped no earlier than it, as the frame and offset are
        try (TipFixture f = new TipFixture()) {
            DispenseService svc = f.svc;
            long ahead = System.currentTimeMillis() + 60_000;
            svc.updateConfigAtomically(live -> {
                live.needleChangedAtMs = ahead;
                return live;
            });
            f.frame(-2.59);
            assertTrue(svc.getConfig().isNeedleAnchored());
            assertFalse(svc.isNeedleXyChecked());
            f.land(0.1, 0.1);
            assertTrue(svc.isNeedleXyChecked(), svc.needleXyProblem());
            assertEquals(ahead, (long) svc.getConfig().needleLanding.setAtMs);
        }
    }
}
