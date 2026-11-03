package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * The dispense recipe (bench p18_board1, 2026-10-28): whole-step screw turns that divide a
 * revolution, the start angle quantised to the 1600 steps of a revolution, and the phase turn of
 * the first purge dot.
 */
public class DispenseProfileTest {
    static final DispenseConfig C = new DispenseConfig();

    @Test
    public void defaultsAreTheBenchRecipeAndValidate() {
        DispenseProfile p = new DispenseProfile();
        assertEquals(18, p.pressurePsi, 0);
        assertEquals(800, p.netStepsPerDot);
        assertEquals(10, p.suckBackDeg, 0);
        assertEquals(298, p.startAngleDeg, 0);
        assertEquals(180, p.screwRpm, 0);
        assertEquals(20000, p.screwAccelDegS2, 0);
        assertEquals(250, p.dwellMs);
        assertEquals(0.10, p.gapMm, 0);
        assertEquals(8, p.purgeDots);
        assertEquals(null, p.startAngleSetAtMs);
        assertTrue(p.validate(C).isEmpty(), p.validate(C).toString());
        assertTrue(new DispenseConfig().validate().isEmpty());
        assertEquals(0.10, new DispenseConfig().gapMm(), 0);
    }

    @Test
    public void turnArithmeticInWholeSteps() {
        DispenseProfile p = new DispenseProfile();
        assertEquals(44, p.suckBackSteps(C), "round(10 x 4.44 = 44.4)");
        assertEquals(800 / 4.44, p.netDeg(C), 1e-12);
        assertEquals(180.1802, p.netDeg(C), 1e-4);
        assertEquals((800 + 44) / 4.44, p.forwardDeg(C), 1e-12);
        assertEquals(190.0901, p.forwardDeg(C), 1e-4);
        assertEquals(0.5, p.revolutionsPerDot(C), 0);
        assertEquals(1080, p.feedDegS(), 1e-12);
        assertEquals("64800", GcodeStreamBuilder.f0(p.feedDegS() * 60), "G1 B ... F64800 at 180 rpm");
        assertTrue(p.isPartialTurn(C));
        p.netStepsPerDot = 1600;
        assertFalse(p.isPartialTurn(C));
        p.netStepsPerDot = 3200;
        assertFalse(p.isPartialTurn(C));
        p.netStepsPerDot = 400;
        assertTrue(p.isPartialTurn(C));
        p.suckBackDeg = 0;
        assertEquals(0, p.suckBackSteps(C));
        assertEquals(p.netDeg(C), p.forwardDeg(C), 0);
    }

    /**
     * A dot turns a quarter, half or whole revolution (R1): the angle repeats. Review 1: two turns (3200 steps)
     * passed here while the stream validator caps a purge dot, which turns the profile's dose, at one revolution
     * and the 20° phase turn, so every board run of such a profile was refused only after its home and Z check.
     */
    @Test
    public void netStepsPerDotMustRepeatTheAngle() {
        for (int ok : new int[] {1600, 800, 400}) {
            DispenseProfile p = new DispenseProfile();
            p.netStepsPerDot = ok;
            assertTrue(p.validate(C).isEmpty(), ok + ": " + p.validate(C));
        }
        for (int bad : new int[] {3200, 200, 100, 600, 0, -800, 4800, 1000, 2400}) {
            DispenseProfile p = new DispenseProfile();
            p.netStepsPerDot = bad;
            List<String> v = p.validate(C);
            assertEquals(Arrays.asList("profile netStepsPerDot must be 400, 800 or 1600 steps (a quarter, half or "
                    + "whole turn of the 1600 steps of a revolution): the start angle repeats and a purge dot turns "
                    + "at most one revolution"), v, "" + bad);
        }
    }

    @Test
    public void startAnglesAreQuantisedToTheStepsOfARevolution() {
        DispenseProfile p = new DispenseProfile();
        // 298 deg = 1324.4 steps -> 1324 -> 297.9 deg; half turns alternate with 117.9 deg
        assertEquals(1324, p.startAngleSteps(C));
        assertEquals(2, p.startAngles(C).size());
        assertEquals(297.9, p.startAngles(C).get(0), 1e-9);
        assertEquals(117.9, p.startAngles(C).get(1), 1e-9);
        p.netStepsPerDot = 1600;
        assertEquals(Arrays.asList(297.9), p.startAngles(C));
        p.netStepsPerDot = 400;
        assertEquals(4, p.startAngles(C).size());
        assertEquals(27.9, p.startAngles(C).get(1), 1e-9);
        p.startAngleDeg = 359.95; // rounds to 1600 = 0
        assertEquals(0, p.startAngleSteps(C));
        p.startAngleDeg = 0;
        assertEquals(0, p.startAngleSteps(C));
        assertEquals(359.775, DispenseProfile.angleOfCount(-1, 1600), 1e-12);
        assertEquals(297.9, DispenseProfile.angleOfCount(1324 + 1600 * 7, 1600), 1e-12);
        assertEquals(117.9, DispenseProfile.angleOfCount(-1076, 1600), 1e-12);
    }

    /**
     * The first purge dot's net turn: from any rest count, after it and 7 more profile dots the
     * screw rests at the start angle; at least 20 deg (89 steps), at most one revolution more.
     */
    @Test
    public void phaseStepsSetTheStartAngleAfterThePurge() {
        DispenseProfile p = new DispenseProfile();
        for (int sign : new int[] {1, -1}) {
            DispenseConfig c = new DispenseConfig();
            c.bExtrudeSign = sign;
            for (long rest = -3300; rest <= 3300; rest += 7) {
                long n = p.phaseSteps(c, rest, 7);
                assertTrue(n >= 89 && n < 1600 + 89, "sign " + sign + " rest " + rest + ": " + n);
                long work = rest + sign * (n + 7 * 800L);
                assertEquals(1324, Math.floorMod(work, 1600), "sign " + sign + " rest " + rest);
            }
        }
        // counts that need (almost) no phase turn get a whole revolution more (MIN_PHASE_TURN_DEG 20)
        assertEquals(1600, p.phaseSteps(C, 524, 7));
        assertEquals(1600 + 88, p.phaseSteps(C, 524 - 88, 7));
        assertEquals(89, p.phaseSteps(C, 524 - 89, 7));
        assertEquals(524, p.phaseSteps(C, 0, 7));
        // no dots after it: the purge dot itself lands on the start angle
        assertEquals(1324, p.phaseSteps(C, 0, 0));
        DispenseConfig neg = new DispenseConfig();
        neg.bExtrudeSign = -1;
        assertEquals(1600 - 1324, p.phaseSteps(neg, 0, 0));
    }

    /**
     * The phase dot of a block switch (profile calibration design §1.2 R3): after it and F flush
     * dots of the new dose, the screw rests at the start angle, for every dose.
     */
    @Test
    public void phaseStepsForAnyDose() {
        DispenseProfile p = new DispenseProfile();
        for (int sign : new int[] {1, -1}) {
            DispenseConfig c = new DispenseConfig();
            c.bExtrudeSign = sign;
            for (int s : new int[] {400, 800, 1600}) {
                for (long rest = -3300; rest <= 3300; rest += 13) {
                    long n = p.phaseSteps(c, rest, 4, s);
                    assertTrue(n >= 89 && n < 1600 + 89, "sign " + sign + " s " + s + " rest " + rest + ": " + n);
                    assertEquals(1324, Math.floorMod(rest + sign * (n + 4L * s), 1600), "s " + s + " rest " + rest);
                }
            }
        }
        // the three-argument form is the profile's own dose
        for (long rest = 0; rest < 1600; rest += 37) {
            assertEquals(p.phaseSteps(C, rest, 7), p.phaseSteps(C, rest, 7, 800));
        }
        // whole turns need no phase dot, but the arithmetic holds: a quarter-turn block after a half-turn one
        assertEquals(524, p.phaseSteps(C, 0, 7, 800));
        assertEquals(Math.floorMod(1324 - 4 * 400, 1600), p.phaseSteps(C, 0, 4, 400));
    }

    /**
     * The dose recipe (design §1.7, §5.4): what sets the paste a turn lays, without the net turn
     * (a record's own), the start angle (its own rule), the purge count or the name.
     */
    @Test
    public void doseFingerprintIsTheRecipeWithoutTheTurn() {
        DispenseProfile base = new DispenseProfile();
        assertEquals("psi=18.000 suck=10.000 rpm=180.000 acc=20000.0 dwell=250 gap=0.1000", base.doseFingerprint());
        List<Consumer<DispenseProfile>> recipe = Arrays.asList(p -> p.dwellMs = 300, p -> p.pressurePsi = 17,
                p -> p.gapMm = 0.12, p -> p.suckBackDeg = 12, p -> p.screwRpm = 200, p -> p.screwAccelDegS2 = 15000);
        for (Consumer<DispenseProfile> ch : recipe) {
            DispenseProfile p = new DispenseProfile();
            ch.accept(p);
            assertNotEquals(base.doseFingerprint(), p.doseFingerprint(), p.doseFingerprint());
        }
        List<Consumer<DispenseProfile>> notRecipe = Arrays.asList(p -> p.netStepsPerDot = 1600,
                p -> p.startAngleDeg = 118, p -> p.purgeDots = 6, p -> p.name = "x", p -> p.startAngleSetAtMs = 5L,
                p -> p.sustainedFeedMgS = 3.0, p -> p.notes = "y");
        for (Consumer<DispenseProfile> ch : notRecipe) {
            DispenseProfile p = new DispenseProfile();
            ch.accept(p);
            assertEquals(base.doseFingerprint(), p.doseFingerprint());
        }
        DispenseProfile p = new DispenseProfile();
        p.dwellMs = 300;
        p.pressurePsi = 17.5;
        assertEquals("psi 18 -> 17.5, dwell 250 -> 300",
                DispenseProfile.fingerprintDiff(base.doseFingerprint(), p.doseFingerprint()));
        assertEquals("", DispenseProfile.fingerprintDiff(base.doseFingerprint(), base.doseFingerprint()));
        assertEquals("psi none -> 18, suck none -> 10, rpm none -> 180, acc none -> 20000, dwell none -> 250, "
                + "gap none -> 0.1", DispenseProfile.fingerprintDiff(null, base.doseFingerprint()));
    }

    /** R7: the highest paste demand a block may ask (2.56 mg/s sustained at 18 psi on the bench). */
    @Test
    public void sustainedFeed() {
        DispenseProfile p = new DispenseProfile();
        assertEquals(2.6, p.sustainedFeedMgS, 0);
        p.sustainedFeedMgS = 3.1;
        assertEquals(3.1, p.copy().sustainedFeedMgS, 0);
        assertEquals(new DispenseProfile().fingerprint(), p.fingerprint(), "a limit, not part of a dot");
        assertRefused(x -> x.sustainedFeedMgS = 0, "sustainedFeedMgS");
        assertRefused(x -> x.sustainedFeedMgS = 11, "sustainedFeedMgS");
        DispenseConfig old = DispenseConfig.fromJson("{\"profile\":{\"dwellMs\":250}}");
        assertEquals(2.6, old.profile.sustainedFeedMgS, 0, "older files get the default");
    }

    /** Screw angle = Marlin's M114 step count of B ("Count ... B:<n>"), never the B position. */
    @Test
    public void stepCountFromMarlinsM114() {
        assertEquals(1234L, DispenseService.parseBCount(Arrays.asList(
                "X:1.00 Y:2.00 Z:3.00 A:0.00 B:4.50 Count X:320 Y:640 Z:120 A:0 B:1234"), "B"));
        assertEquals(-5L, DispenseService.parseBCount(Arrays.asList(
                "X:0.00 Y:0.00 Z:0.00 A:0.00 B:-0.90 Count X:0 Y:0 Z:0 A:0 B:-5"), "B"));
        assertEquals(77L, DispenseService.parseBCount(Arrays.asList("echo:busy: processing",
                "X:1.00 Y:2.00 Z:3.00 A:0.00 B:4.50 Count X:0 Y:0 Z:0 B:77", "ok"), "B"));
        // no Count: null; the B in the position part is not a step count
        assertEquals(null, DispenseService.parseBCount(Arrays.asList("X:1.00 Y:2.00 Z:3.00 A:0.00 B:4.50", "ok"), "B"));
        assertEquals(null, DispenseService.parseBCount(Arrays.asList(
                "X:1.00 Y:2.00 Z:3.00 A:0.00 B:4.50 Count X:0 Y:0 Z:0"), "B"));
        assertEquals(null, DispenseService.parseBCount(Arrays.asList("ok"), "B"));
        // another axis letter
        assertEquals(99L, DispenseService.parseBCount(Arrays.asList("X:1.00 J:4.50 Count X:0 J:99"), "J"));
        assertEquals(null, DispenseService.parseBCount(Arrays.asList("X:1.00 J:4.50 Count X:0 B:99"), "J"));
    }

    /** Anything that changes a dot changes the fingerprint (a plan made on another recipe is stale). */
    @Test
    public void fingerprintFollowsWhatChangesADot() {
        String base = new DispenseProfile().fingerprint();
        List<Consumer<DispenseProfile>> changes = Arrays.asList(p -> p.dwellMs = 300, p -> p.pressurePsi = 17,
                p -> p.gapMm = 0.12, p -> p.netStepsPerDot = 1600, p -> p.suckBackDeg = 12,
                p -> p.startAngleDeg = 118, p -> p.screwRpm = 200, p -> p.screwAccelDegS2 = 15000,
                p -> p.purgeDots = 6);
        for (Consumer<DispenseProfile> ch : changes) {
            DispenseProfile p = new DispenseProfile();
            ch.accept(p);
            assertNotEquals(base, p.fingerprint(), p.fingerprint());
        }
        // confirming the start angle, the name and notes do not make a plan stale
        DispenseProfile p = new DispenseProfile();
        p.startAngleSetAtMs = 123L;
        p.name = "renamed";
        p.notes = "x";
        assertEquals(base, p.fingerprint());
        assertEquals(base, p.copy().fingerprint());
        assertEquals(123L, (long) p.copy().startAngleSetAtMs);
        assertEquals("renamed", p.copy().name);
    }

    @Test
    public void validateRefusesUnsafeOrUnmeasuredValues() {
        assertRefused(p -> p.pressurePsi = 22, "pressurePsi");
        assertRefused(p -> p.pressurePsi = 0, "pressurePsi");
        assertRefused(p -> p.gapMm = 0.20, "gapMm");
        assertRefused(p -> p.gapMm = 0.04, "gapMm");
        assertRefused(p -> p.screwRpm = 401, "screwRpm");
        assertRefused(p -> p.screwAccelDegS2 = 500, "screwAccelDegS2");
        assertRefused(p -> p.dwellMs = -1, "dwellMs");
        assertRefused(p -> p.suckBackDeg = 31, "suckBackDeg");
        assertRefused(p -> p.startAngleDeg = 360, "startAngleDeg");
        assertRefused(p -> p.purgeDots = 61, "purgeDots");
        // the first purge dot sets the start angle of a partial turn and a normal dot must flush it before
        // the work: purge 0 and 1 are refused for a half turn
        assertRefused(p -> p.purgeDots = 0, "purgeDots must be at least 2");
        assertRefused(p -> p.purgeDots = 1, "purgeDots must be at least 2");
        DispenseProfile full = new DispenseProfile();
        full.netStepsPerDot = 1600;
        full.purgeDots = 0;
        assertTrue(full.validate(C).isEmpty(), full.validate(C).toString());
        // the screw feed must stay under the B axis limit
        DispenseConfig slow = new DispenseConfig();
        slow.bMaxFeedDegS = 1000;
        List<String> v = slow.profile.validate(slow);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("above the B axis limit"), v.toString());
        // the config's validate includes the profile's
        DispenseConfig c = new DispenseConfig();
        c.profile.netStepsPerDot = 600;
        assertFalse(c.validate().isEmpty());
    }

    static void assertRefused(Consumer<DispenseProfile> change, String text) {
        DispenseProfile p = new DispenseProfile();
        change.accept(p);
        List<String> v = p.validate(C);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains(text), v.toString());
    }
}
