package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * The SIM needle's paste (PROFILE_CALIBRATION_DESIGN.md §11.2) on a fake clock: the mass of a turn
 * at known physical angles, net-zero turns, the transient after an idle, the tip bead, gap misses and
 * smears, the feed limit, threads by mass, the Count line, reconnects, coupons, and a weighing that
 * finds the SIM's 2.30 mg/rev rather than the engine's 2.11.
 */
public class SimPasteTest {
    /** The physical angle of the ripple peak (steps) of the §11.3 scene: 299°. */
    static final double PEAK_STEPS = 299 / 360.0 * SimPaste.STEPS_PER_REV;
    /** Needle-touch Z of the blank (flat) and of the coupons, with a needle Z offset of 0. */
    static final double BLANK_Z = 5.62, COUPON_Z = PadcalScenes.COUPON_Z;
    final long[] nowNs = {0};

    /** The §11.3 scene on a flat blank, no board. */
    static SimScene scene() {
        SimScene s = new SimScene();
        s.boardFile = "toy.board.xml";
        s.seat = SimScene.seat(0, 0, 0, 1, 1, 0); // no board size: nothing lies on a board
        s.blankSurface = new SimScene.Plane(BLANK_Z, 0, 0);
        return PadcalScenes.padcal(s, "connected");
    }

    /** The bare law: no noise, transient, carry-over, feed limit, miss, thread or landing scatter; offset 0. */
    static SimScene exact() {
        SimScene s = scene();
        SimScene.Paste p = s.paste;
        p.cv = 0;
        p.oozeFirst = 0;
        p.refillDip = 0;
        p.transferRatio = 1;
        p.missBelowMg = 0;
        p.feedLimitMgS = 1e9;
        p.landingSdMm = 0;
        p.threadShare = 0;
        p.offsetSteps = 0;
        return s;
    }

    SimPaste paste(SimScene s) {
        return new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> nowNs[0], () -> 0.0);
    }

    static SimNeedle needle(SimPaste p, SimScene s) {
        return new SimNeedle(p, s.paste.n2OffsetMm);
    }

    void at(double seconds) {
        nowNs[0] = Math.round(seconds * 1e9);
    }

    /** One dot of {@code net} steps on the blank starting at a physical count. */
    double oneDot(double ripple, long startCount, long net) {
        SimScene s = exact();
        s.paste.ripple = ripple;
        SimPaste p = paste(s);
        p.setCount(startCount);
        SimNeedle n = needle(p, s);
        n.pressurize();
        n.dot(300, 240, BLANK_Z, net);
        List<SimPaste.Deposit> d = p.deposits("blank");
        assertEquals(1, d.size());
        assertEquals(startCount, (long) d.get(0).startCount);
        return d.get(0).mg;
    }

    @Test
    public void aTurnLaysItsIntegralOfTheRippleFactorSincHalfTheTurn() {
        // §1.2: m = mRev·T/1600·[1 + ripple·sinc(T/2)·cos(φ + T/2 − ψ)]; sinc 0.90 / 0.64 / 0 for 400 / 800 / 1600.
        double[] sinc = {Math.sin(Math.PI / 4) / (Math.PI / 4), 2 / Math.PI, 0};
        long[] turns = {400, 800, 1600};
        for (int i = 0; i < 3; i++) {
            long t = turns[i];
            double nominal = 2.30 * t / 1600;
            double peak = oneDot(0.15, Math.round(PEAK_STEPS - t / 2.0), t);
            double trough = oneDot(0.15, Math.round(PEAK_STEPS + 800 - t / 2.0), t);
            assertEquals(sinc[i], (peak / nominal - 1) / 0.15, 0.002, t + " steps centred on the peak");
            assertEquals(sinc[i], (1 - trough / nominal) / 0.15, 0.002, t + " steps centred on the trough");
            assertEquals(nominal, oneDot(0, 123, t), 1e-12, "no ripple: mRev·T/1600");
        }
        assertEquals(0.900, sinc[0], 0.001);
        assertEquals(0.637, sinc[1], 0.001);
    }

    @Test
    public void aNetZeroTurnLaysNothing() {
        SimScene s = exact();
        s.paste.ripple = 0;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.suckSteps = 200;
        n.pressurize();
        n.dot(300, 240, BLANK_Z, 0); // forward 200, back 200
        assertTrue(p.deposits(null).isEmpty());
        n.dot(303, 240, BLANK_Z, 800);
        assertEquals(1.15, p.deposits("blank").get(0).mg, 1e-9, "the next half turn is a whole dot");
    }

    @Test
    public void withoutPressureTheScrewTurnsButLaysNothing() {
        SimScene s = exact();
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.send("G92 B0");
        n.dot(300, 240, BLANK_Z, 800);
        n.send("M106 P2 S255"); // the pump alone is not the pressure
        n.dot(303, 240, BLANK_Z, 800);
        assertTrue(p.deposits(null).isEmpty());
        assertEquals(1600, p.count(), "the count follows the screw all the same");
        n.send("M106 P3 S255");
        n.dot(306, 240, BLANK_Z, 800);
        assertEquals(1, p.deposits(null).size());
    }

    @Test
    public void theFirstDotsOfASessionFollowTheIdleBeforeIt() {
        SimScene s = exact();
        s.paste.ripple = 0;
        s.paste.oozeFirst = 0.40;
        s.paste.refillDip = 0.30;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        // A fresh store: a full idle (g = 1), the ooze, then the refill dip decaying over 2 dots.
        n.pressurize();
        n.row(290, 230, 2.5, 6, BLANK_Z, 800);
        n.vent();
        List<SimPaste.Deposit> d = p.deposits(null);
        double[] f = {1.40, 0.70, 1 - 0.30 * Math.exp(-0.5), 1 - 0.30 * Math.exp(-1), 1 - 0.30 * Math.exp(-1.5),
                1 - 0.30 * Math.exp(-2)};
        for (int i = 0; i < 6; i++) {
            assertEquals(1.15 * f[i], d.get(i).mg, 1e-9, "session 1 dot " + i);
        }
        // 100 s later: g = idle / 300 from the model clock.
        at(100);
        n.pressurize();
        n.row(290, 235, 2.5, 3, BLANK_Z, 800);
        n.vent();
        d = p.deposits(null);
        double g = (d.get(6).atS - d.get(5).atS) / 300;
        assertTrue(g > 0.30 && g < 0.34, "about 100 s of idle: " + g);
        assertEquals(1.15 * (1 + 0.40 * g), d.get(6).mg, 1e-9);
        assertEquals(1.15 * (1 - 0.30 * g), d.get(7).mg, 1e-9);
        // After idleDeadS (3600 s) with the pressure off the first two dots lay nothing.
        at(4000);
        n.pressurize();
        n.row(290, 240, 2.5, 3, BLANK_Z, 800);
        n.vent();
        d = p.deposits(null);
        assertEquals(10, d.size(), "two dead dots, one laid");
        assertEquals(1.15 * (1 - 0.30 * Math.exp(-0.5)), d.get(9).mg, 1e-9, "the third dot of the session");
        assertEquals(290 + 5, d.get(9).x, 1e-9);
    }

    @Test
    public void theTipBeadCarriesPasteOverToTheNextDots() {
        SimScene s = exact();
        s.paste.ripple = 0;
        s.paste.transferRatio = 0.75;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.pressurize();
        n.row(290, 230, 2.5, 8, BLANK_Z, 1600);
        n.row(290, 235, 2.5, 6, BLANK_Z, 400);
        List<SimPaste.Deposit> d = p.deposits(null);
        assertEquals(0.75 * 2.30, d.get(0).mg, 1e-9, "the first dot takes 3/4 of its paste");
        double bead = 2.30 / 3 * (1 - Math.pow(0.25, 8)); // after the one-turn dots: almost m/3
        double excess = 0.75 * (bead + 0.575) - 0.575;
        for (int k = 0; k < 6; k++) {
            assertEquals(0.575 + excess * Math.pow(0.25, k), d.get(8 + k).mg, 1e-9, "quarter dot " + k + " after the "
                    + "switch: the carried excess decays by 1 − transferRatio per dot");
        }
        assertTrue(d.get(8).mg > 1.7 * 0.575, "the first small dot after big ones is far too big");
    }

    @Test
    public void tooHighIsAMissAndBelowTheSurfaceASmear() {
        SimScene s = exact();
        s.paste.ripple = 0;
        s.paste.transferRatio = 0.75;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.pressurize();
        n.dotAt(300, 240, BLANK_Z + 0.25, BLANK_Z + 1.25, 800); // gap 0.25 > maxGapMm 0.20
        assertTrue(p.deposits(null).isEmpty(), "nothing reaches the surface");
        assertEquals(1.15, p.beadMg(), 1e-9, "it all hangs on the tip");
        n.dotAt(303, 240, BLANK_Z + 0.19, BLANK_Z + 1.19, 800); // just inside the limit: laid
        SimPaste.Deposit d = p.deposits(null).get(0);
        assertEquals(0.75 * 2.30, d.mg, 1e-9, "the next dot takes the bead with it");
        assertFalse(d.string);
        n.dotAt(306, 240, BLANK_Z - 0.05, BLANK_Z + 1, 800);
        d = p.deposits(null).get(1);
        assertTrue(d.string, "a needle below the surface smears its dot");
        assertEquals(2, d.aMm / d.bMm, 1e-9);
    }

    @Test
    public void theScrewCannotPumpFasterThanThePasteFeedsIt() {
        SimScene s = exact();
        s.paste.ripple = 0;
        s.paste.feedLimitMgS = 3.0;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.pressurize();
        n.row(285, 230, 2.5, 30, BLANK_Z, 1600); // one turn every ~0.85 s: 2.7 mg/s
        for (SimPaste.Deposit d : p.deposits(null)) {
            assertEquals(2.30, d.mg, 1e-9, "fed: every one-turn dot is whole");
        }
        n.dwellMs = 0; // one turn every ~0.6 s: 3.8 mg/s asked
        n.row(285, 235, 2.5, 30, BLANK_Z, 1600);
        n.row(285, 240, 2.5, 30, BLANK_Z, 1600);
        List<SimPaste.Deposit> d = p.deposits(null);
        double sum = 0;
        for (int i = 61; i < 90; i++) {
            sum += d.get(i).mg;
        }
        double rate = sum / (d.get(89).atS - d.get(60).atS);
        assertTrue(rate <= 3.0 && rate > 2.4, "starving: the screw lays no more than the paste feeds, " + rate);
        assertTrue(d.get(89).mg < 0.9 * 2.30, "the starved dots shrink: " + d.get(89).mg);
    }

    @Test
    public void smallDotsDrawThreadsAlongTheHop() {
        double[] mRev = {1.2, 1.6, 2.4}; // quarter dots of 0.30, 0.40, 0.60 mg (the first one 3/4 of it)
        int[] threads = new int[3];
        for (int i = 0; i < 3; i++) {
            SimScene s = exact();
            s.paste.ripple = 0;
            s.paste.transferRatio = 0.75; // the thread's paste comes from the tip bead
            s.paste.mRevMg = mRev[i];
            s.paste.threadShare = 1;
            SimPaste p = paste(s);
            SimNeedle n = needle(p, s);
            n.pressurize();
            n.row(285, 230, 2.5, 30, BLANK_Z, 400);
            for (SimPaste.Deposit d : p.deposits(null)) {
                threads[i] += d.thread ? 1 : 0;
            }
            if (i == 0) {
                SimPaste.Deposit dot = p.deposits(null).get(0);
                SimPaste.Deposit t = p.deposits(null).get(1);
                assertTrue(t.thread);
                assertEquals(0.75, t.aMm, 1e-9, "0.6 × the 2.5 mm hop long");
                assertEquals(0.075, t.bMm, 1e-9, "0.15 mm wide");
                assertEquals(0, t.angleDeg, 1e-9, "along the hop (+X)");
                assertEquals(dot.x + dot.diameterMm() / 2 + 0.75, t.x, 1e-9, "from the dot's edge");
                assertEquals(SimPaste.THREAD_MG, t.mg, 0, "0.02 mg, from the tip bead");
            }
        }
        assertEquals(29, threads[0], "0.30 mg ≤ threadFullMg: every hop");
        assertTrue(threads[1] >= 8 && threads[1] <= 23, "0.40 mg: about half of them, " + threads[1]);
        assertEquals(0, threads[2], "0.45 mg and more (threadZeroMg): none");
        // A lift to the travel height between the dots breaks every thread.
        SimScene s = exact();
        s.paste.ripple = 0;
        s.paste.mRevMg = 1.2;
        s.paste.threadShare = 1;
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.pressurize();
        for (int i = 0; i < 10; i++) {
            n.travel(285 + 2.5 * i, 230);
            n.dot(285 + 2.5 * i, 230, BLANK_Z, 400);
        }
        assertEquals(10, p.deposits(null).size(), "dots, no thread");
    }

    @Test
    public void m114ReportsMarlinsCountWhichAReconnectResets() {
        SimScene s = scene();
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        assertEquals(new Random(11 + p.connectionNumber()).nextInt(1600), p.offsetSteps(), "Random(seed + connection)");
        int offset0 = p.offsetSteps();
        n.pressurize();
        n.row(290, 230, 2.5, 3, BLANK_Z, 800);
        n.vent(); // G92 B0: the logical B restarts, the count does not
        assertEquals("Count X:0 Y:0 Z:0 B:2400", n.send("M114"));
        assertNull(n.send("M114.1"));
        assertNull(n.send("G1 B0 F64800"), "other lines get no extra reply");
        assertEquals(2400, p.count(), "B 0 right after G92 B0 does not move the screw");
        n.send(String.format(java.util.Locale.ROOT, "G1 B%.4f F64800", -800 / SimPaste.STEPS_PER_DEG));
        assertEquals(1600, p.count(), "a move back from the new origin turns the screw back");
        p.powerCycle();
        assertEquals(0, p.count(), "a power cycle restarts the count");
        assertNotEquals(offset0, p.offsetSteps(), "and draws the physical offset again");
        assertEquals(3, p.deposits(null).size(), "the paste on the blank stays");
        s.paste.reportCount = false;
        assertNull(n.send("M114"), "reportCount false: OpenPnP's simulator without Count");
        s.paste.reportCount = true;
        s.paste.offsetSteps = 1234;
        assertEquals(1234, p.offsetSteps(), "a scene may pin the offset");
        SimPaste again = paste(s);
        assertEquals(p.connectionNumber() + 1, again.connectionNumber(), "a new connection on the same store");
        assertEquals(3, again.deposits(null).size());
    }

    @Test
    public void withoutAPasteBlockNothingIsLaidOrReported() {
        SimScene s = scene();
        s.paste = null;
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, new double[] {25.291, -75.926});
        n.pressurize();
        n.row(290, 230, 2.5, 3, BLANK_Z, 800);
        assertNull(n.send("M114"));
        assertTrue(p.deposits(null).isEmpty());
        assertFalse(p.isActive());
        assertEquals(2400, p.count(), "the screw is followed all the same");
        s.paste = new SimScene.Paste(); // written into the running session
        assertEquals("Count X:0 Y:0 Z:0 B:2400", n.send("M114"));
    }

    @Test
    public void dotsLandAtTheNeedleOffsetOnTheSurfaceUnderIt() {
        SimScene s = exact();
        s.paste.landingBiasMm = new double[] {0.2, -0.1};
        SimPaste p = paste(s);
        SimNeedle n = new SimNeedle(p, new double[] {25.291, -75.926});
        n.pressurize();
        n.dot(300, 240, BLANK_Z, 800);
        SimPaste.Deposit d = p.deposits("blank").get(0);
        assertEquals(300.2, d.x, 1e-9, "raw head X + n2OffsetMm + the landing bias");
        assertEquals(239.9, d.y, 1e-9);
        assertEquals(SimScene.Region.BLANK, d.region);
        // A coupon has its own plane: 5.80, not the blank's 5.62.
        n.dot(350, 55, COUPON_Z, 800);
        d = p.deposits("coupon1").get(0);
        assertEquals(SimScene.Region.COUPON, d.region);
        assertEquals("coupon1", d.place);
        assertFalse(d.string);
        n.dot(350, 60, BLANK_Z, 800); // the needle at the blank's height: 0.08 below the coupon
        assertTrue(p.deposits("coupon1").get(1).string, "smeared on the coupon");
        assertEquals(p.deposits("coupon1").get(0).mg + p.deposits("coupon1").get(1).mg, p.couponMassMg("coupon1"),
                1e-12);
        assertTrue(p.deposits("coupon2").isEmpty());
        n.dot(200, 100, BLANK_Z, 800); // over the bed (2.5) at the blank's height: in the air
        assertEquals(3, p.deposits(null).size());
        assertEquals(2, p.wipe("coupon1"));
        assertEquals(0, p.couponMassMg("coupon1"), 0);
        assertEquals(1, p.deposits(null).size());
    }

    @Test
    public void depositsAreSeenThroughTheSceneAndCountVersions() {
        SimScene s = exact();
        assertTrue(SimPaste.depositsFor(s).isEmpty(), "nothing laid yet: no store is made for the camera");
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        long v0 = SimPaste.version();
        n.pressurize();
        n.dot(300, 240, BLANK_Z, 800);
        assertTrue(SimPaste.version() > v0, "a deposit moves the version");
        assertEquals(1, SimPaste.depositsFor(s).size(), "the renderer's view of the same scene object");
        long v1 = SimPaste.version();
        p.wipe("blank");
        assertTrue(SimPaste.version() > v1, "so does a wipe");
        assertTrue(SimPaste.depositsFor(s).isEmpty());
    }

    @Test
    public void aWeighingFindsTheSimsMassPerTurnNotTheModels() {
        // §14.3: 136 quarter-turn dots on coupon 1, 68 half-turn dots on coupon 2, each after a purge of 8
        // half turns on the blank: about 0.575 and 1.15 mg per dot (the SIM's 2.30 mg/rev, ±4 %), not 0.53 / 1.06.
        SimScene s = scene();
        SimPaste p = paste(s);
        SimNeedle n = needle(p, s);
        n.pressurize();
        n.row(285, 230, 2.5, 8, BLANK_Z, 800);
        n.travel(334, 44);
        for (int r = 0; r < 8; r++) {
            n.row(334, 44 + 2.0 * r, 2.0, 17, COUPON_Z, 400);
        }
        n.vent();
        at(60);
        n.pressurize();
        n.row(285, 235, 2.5, 8, BLANK_Z, 800);
        n.travel(332, 79);
        for (int r = 0; r < 4; r++) {
            n.row(332, 79 + 2.5 * r, 2.2, 17, COUPON_Z, 800);
        }
        n.vent();
        assertEquals(136, SimNeedle.dots(p, "coupon1").size(), "every quarter dot transferred");
        assertEquals(68, SimNeedle.dots(p, "coupon2").size());
        double q = p.couponMassMg("coupon1") / 136, h = p.couponMassMg("coupon2") / 68;
        assertEquals(0.575, q, 0.575 * 0.04, "quarter turn");
        assertEquals(1.15, h, 1.15 * 0.04, "half turn");
        assertEquals(q / 400, h / 800, h / 800 * 0.05, "mass per step agrees within 5 % (P1)");
        assertTrue(Math.abs(q - 0.528) / 0.528 > 0.05, "not the engine's 0.528 mg");
    }
}
