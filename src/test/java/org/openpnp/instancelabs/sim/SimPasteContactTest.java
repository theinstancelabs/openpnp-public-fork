package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

/**
 * INF-06 §2b (Process 11-05, rule (a)): with {@code perContact} a 25G contact's mass follows the contact history, not
 * the angle. Checked against the W1 weighings (runs/p35/NOTES.md; docs/research/paste-model-reference-per-tip.md §2b):
 * R1 (A 180° from 316, B 90° from 136, the dead quarter 226 turned in the air: 0.975 / 0.975 mg), R2b (the dead air
 * quarter, then a, b, c of 90°: 0 / 0.975 / 0.975) and S0b (one quarter a dot, short hops, no air rotation: never
 * held), every turn 1.95 mg. On the law alone ({@link SimPasteTest#exact()}: no noise, transient, bead, miss, feed
 * limit or landing scatter).
 */
public class SimPasteContactTest {
    static final long Q = SimPaste.STEPS_PER_REV / 4;
    static final double LIVE_MG = 1.95 / 3, Z = SimPasteTest.BLANK_Z;
    /** W1's two blanks: FRONT (blue) and BACK (black) far apart; spots 2.5 mm apart along X. */
    static final double FRONT_X = 285, FRONT_Y = 230, BACK_X = 330, BACK_Y = 245;
    final long[] nowNs = {0};

    /** The 25G at 13.7 psi: 1.95 mg/turn, the dead quarter at physical 226-316, count 0 at physical {@code startDeg}. */
    static SimScene scene(double startDeg) {
        SimScene s = SimPasteTest.exact();
        s.paste.mRevMg = 1.95;
        s.paste.perContact = true;
        s.paste.deadQuarterDeg = 226;
        s.paste.offsetSteps = (int) Math.round(startDeg * SimPaste.STEPS_PER_REV / 360);
        return s;
    }

    SimPaste paste(SimScene s) {
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> nowNs[0], () -> 0.0);
        p.engineAims(() -> null);
        return p;
    }

    /** The screw turned {@code steps} where the needle is (the next head move ends it). */
    static void turn(SimNeedle n, long steps) {
        n.send(String.format(Locale.ROOT, "G1 B%.4f F64800", (n.restSteps + steps) / SimPaste.STEPS_PER_DEG));
        n.restSteps += steps;
    }

    /** Up to travel Z and the screw turned there: an air rotation. */
    static void air(SimNeedle n, double x, double y, long steps) {
        n.travel(x, y);
        turn(n, steps);
    }

    static List<Double> mg(SimPaste p, double y) {
        return SimNeedle.dots(p, null).stream().filter(d -> Math.abs(d.y - y) < 1).map(d -> d.mg)
                .collect(Collectors.toList());
    }

    static double total(SimPaste p) {
        return SimNeedle.dots(p, null).stream().mapToDouble(d -> d.mg).sum();
    }

    @Test
    public void r1AHalfTurnAndAQuarterAfterTheDeadAirQuarterShareTheTurnEvenly() {
        SimScene s = scene(316);
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        int turns = 6;
        for (int t = 0; t < turns; t++) {
            n.dot(FRONT_X + 2.5 * t, FRONT_Y, Z, 2 * Q); // A: the quarters 316 and 46
            n.dot(BACK_X + 2.5 * t, BACK_Y, Z, Q); // B: 136
            air(n, BACK_X + 2.5 * t, BACK_Y, Q); // the dead quarter 226 in the air
        }
        List<Double> a = mg(p, FRONT_Y), b = mg(p, BACK_Y);
        assertEquals(turns, a.size());
        assertEquals(turns, b.size());
        // the first A had no air quarter before it, so it lays its own two quarters and B its own
        assertEquals(2 * LIVE_MG, a.get(0), 1e-9, "turn 1's A");
        assertEquals(LIVE_MG, b.get(0), 1e-9, "turn 1's B");
        for (int t = 1; t < turns; t++) { // measured 0.90 / 0.875 (50.7 : 49.3); the CI band is ±15 %
            assertEquals(0.975, a.get(t), 1e-9, "A of turn " + (t + 1));
            assertEquals(0.975, b.get(t), 1e-9, "B of turn " + (t + 1));
        }
        assertEquals(1.95 * turns, total(p), 1e-9, "each turn's 1.95 mg, conserved");
    }

    @Test
    public void r2bTheContactRightAfterTheDeadAirQuarterIsEmptyAndTheNextTwoCarryTheTurn() {
        SimScene s = scene(226);
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        int turns = 5;
        for (int t = 0; t < turns; t++) {
            air(n, FRONT_X + 2.5 * t, FRONT_Y, Q); // the dead quarter 226 in the air
            n.dot(FRONT_X + 2.5 * t, FRONT_Y, Z, Q); // a: 316
            n.dot(BACK_X + 5 * t, BACK_Y, Z, Q); // b: 46
            n.dot(BACK_X + 5 * t + 2.5, BACK_Y, Z, Q); // c: 136
        }
        assertTrue(mg(p, FRONT_Y).isEmpty(), "a lays nothing (measured 0 ± 0.2 mg): " + mg(p, FRONT_Y));
        List<Double> bc = mg(p, BACK_Y);
        assertEquals(2 * turns, bc.size());
        for (double m : bc) {
            assertEquals(0.975, m, 1e-9, "b and c: " + bc);
        }
        assertEquals(1.95 * turns, total(p), 1e-9, "b + c carry the turn (measured 19.5 mg over 10 turns)");
    }

    @Test
    public void s0bOneQuarterADotWithoutAirRotationIsNeverHeld() {
        SimScene s = scene(226);
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        for (int i = 0; i < 8; i++) { // 226 (dead), 316, 46, 136, twice; short hops
            n.dot(FRONT_X + 2.5 * i, FRONT_Y, Z, Q);
        }
        List<Double> live = mg(p, FRONT_Y);
        assertEquals(6, live.size(), "the dead quarter's contacts lay nothing: " + live);
        for (double m : live) {
            assertEquals(LIVE_MG, m, 1e-9, "each live quarter its own: " + live);
        }
    }

    @Test
    public void onlyAtLeastTheArmingRotationInTheAirHoldsTheNextContact() {
        for (long airSteps : new long[] {Math.round(44 * SimPaste.STEPS_PER_REV / 360.0),
                Math.round(45 * SimPaste.STEPS_PER_REV / 360.0)}) {
            SimScene s = scene(226); // the air rotation on the dead quarter, as measured
            SimPaste p = paste(s);
            SimNeedle n = SimPasteTest.needle(p, s);
            n.pressurize();
            air(n, FRONT_X, FRONT_Y, airSteps);
            n.dot(FRONT_X, FRONT_Y, Z, Q); // its first quarter: dead 45-90 steps, then live
            boolean held = mg(p, FRONT_Y).isEmpty();
            assertEquals(airSteps >= 200, held, airSteps + " steps in the air: " + mg(p, FRONT_Y));
        }
    }

    @Test
    public void aLongStillDwellInTheAirArmsTheHoldOnlyWithHoldArmAirSet() {
        for (Double holdS : new Double[] {null, 1.0}) {
            SimScene s = scene(316);
            s.paste.holdArmAirS = holdS;
            SimPaste p = paste(s);
            SimNeedle n = SimPasteTest.needle(p, s);
            n.pressurize();
            n.dot(FRONT_X, FRONT_Y, Z, Q); // 316: live, laid
            n.travel(BACK_X, BACK_Y);
            n.send("G4 P1500"); // 1.5 s in the air, the screw still
            n.dot(BACK_X, BACK_Y, Z, Q); // 46
            List<Double> back = mg(p, BACK_Y);
            if (holdS == null) {
                assertEquals(1, back.size(), "no time trigger by default (Process 11-05)");
                assertEquals(LIVE_MG, back.get(0), 1e-9);
            }
            else {
                assertTrue(back.isEmpty(), "1.5 s in the air with holdArmAirS 1.0 holds its first quarter: " + back);
            }
        }
    }

    @Test
    public void aHalfTurnOverALiveThenADeadQuarterPassesNewPasteThroughAndDrainsTheBeadEvenly() {
        // model choice (Process 11-05): pass-through supported by the 18:46 map, the bead's linear drain unmeasured
        SimScene s = scene(316);
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        air(n, FRONT_X, FRONT_Y, Q); // 316, live, in the air: the bead 0.65, armed
        n.dot(FRONT_X, FRONT_Y, Z, Q); // 46, held: the bead 1.30
        n.dot(FRONT_X + 2.5, FRONT_Y, Z, Q); // 136, live: its own quarter + half the bead
        n.dot(FRONT_X + 5, FRONT_Y, Z, Q); // 226, dead: the bead's other half
        List<Double> laid = mg(p, FRONT_Y);
        assertEquals(2, laid.size(), "" + laid);
        assertEquals(2 * LIVE_MG, laid.get(0), 1e-9, "" + laid);
        assertEquals(LIVE_MG, laid.get(1), 1e-9, "" + laid);
    }

    @Test
    public void pastePushedInTheAirJoinsThePoolAndEveryMilligramIsLaid() {
        // unmeasured (Process 11-05): a live quarter turned in the air, and a short air turn inside an open window
        SimScene s = scene(136);
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        air(n, FRONT_X, FRONT_Y, Q); // 136, live: 0.65 mg into the pool, armed
        n.dot(FRONT_X, FRONT_Y, Z, Q); // 226, dead: held; the window opens at 316
        n.dot(FRONT_X + 2.5, FRONT_Y, Z, Q); // 316: 0.975
        air(n, FRONT_X + 5, FRONT_Y, Math.round(30 * SimPaste.STEPS_PER_REV / 360.0)); // 46-76, live, no arming
        for (int i = 0; i < 3; i++) { // 76, 166, 256: the window's rest, then the pool with the next contact
            n.dot(FRONT_X + 5 + 2.5 * i, FRONT_Y, Z, Q);
        }
        List<Double> laid = mg(p, FRONT_Y);
        double pushed = LIVE_MG / Q * (Q + 0 + Q + 133 + Q + 267 + 133); // live steps of each rotation above
        assertEquals(pushed, total(p), 1e-9, "everything pushed is laid: " + laid);
        assertEquals(4, laid.size(), "the dead contact after the live air quarter holds: " + laid);
        assertEquals(0.975, laid.get(0), 1e-9, "" + laid);
    }

    @Test
    public void perContactLaysSubTurnContactsThatWholeTurnsOnlyWouldRefuse() {
        SimScene s = scene(226);
        s.paste.wholeTurnsOnly = true;
        SimPaste p = paste(s);
        SimNeedle n = SimPasteTest.needle(p, s);
        n.pressurize();
        for (int i = 0; i < 4; i++) {
            n.dot(FRONT_X + 2.5 * i, FRONT_Y, Z, Q);
        }
        assertEquals(3, mg(p, FRONT_Y).size(), "the sub-turn model: " + mg(p, FRONT_Y));
    }

    @Test
    public void theSceneKeysParseOverlayPerTipAndAreChecked() {
        SimScene.Paste base = new SimScene.Paste();
        assertFalse(base.perContact, "off by default: today's SIM");
        assertEquals(226, base.deadQuarterDeg, 0);
        assertEquals(45, base.holdArmAirDeg, 0);
        assertNull(base.holdArmAirS);
        SimScene.Paste p = SimScene.apply(new JsonParser().parse("{\"perContact\": true, \"deadQuarterDeg\": 136, "
                + "\"holdArmAirDeg\": 60, \"holdArmAirS\": 2.5}").getAsJsonObject(), base.copy());
        assertTrue(p.perContact);
        assertEquals(136, p.deadQuarterDeg, 0);
        assertEquals(60, p.holdArmAirDeg, 0);
        assertEquals(2.5, p.holdArmAirS, 0);
        SimScene.Paste copy = p.copy();
        assertTrue(copy.perContact);
        assertEquals(136, copy.deadQuarterDeg, 0);
        assertEquals(60, copy.holdArmAirDeg, 0);
        assertEquals(2.5, copy.holdArmAirS, 0);
        assertNull(SimScene.apply(new JsonParser().parse("{\"holdArmAirS\": null}").getAsJsonObject(), copy)
                .holdArmAirS, "null turns the time trigger off");
        assertThrows(IllegalArgumentException.class, () -> SimScene.apply(
                new JsonParser().parse("{\"perContact\": 1}").getAsJsonObject(), new SimScene.Paste()));
        for (String bad : new String[] {"{\"deadQuarterDeg\": 360}", "{\"deadQuarterDeg\": -1}",
                "{\"holdArmAirDeg\": -1}", "{\"holdArmAirDeg\": 361}", "{\"holdArmAirS\": -0.5}"}) {
            SimScene.Paste q = SimScene.apply(new JsonParser().parse(bad).getAsJsonObject(), new SimScene.Paste());
            assertThrows(IllegalArgumentException.class, () -> SimScene.check(q, "paste: "), bad);
        }
    }
}
