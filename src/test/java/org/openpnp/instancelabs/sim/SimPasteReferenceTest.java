package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * INF-06's acceptance: the SIM's per-tip pastes ({@code src/test/sim/paste-tips.json}) lay dots that match Process's
 * measured reference ({@code docs/research/paste-model-reference-per-tip.md}, 11-04) within its CI tolerance: mass per
 * whole turn ±10 %, mean area ±15 % (phase-averaged: a board's 298°/118° pairs average the phase, Process 05:0x), CV up
 * to 1.5× the measured one (at the balanced phase, where both halves of a turn are equal), landing SD ±0.02 mm per
 * axis. Mass, mean area, swing and gap are checked on the law itself (cv 0); CV and landing scatter with the noise,
 * as their means over 16 noise seeds.
 */
public class SimPasteReferenceTest {
    static final long TURN = SimPaste.STEPS_PER_REV;
    /** The transient after the idle (oozeFirst, refillDip over refillTauDots 2): 12 dots leave < 0.2 % of it. */
    static final int WARM_UP_DOTS = 12;
    /** A run's first dots: the tip bead takes on the run's own rhythm (0.25^4 of the last run's is left). */
    static final int THROWAWAYS = 4;
    /** Noise seeds the CV and landing scatter are averaged over. */
    static final int SEEDS = 16;
    /** Spots on the bench blank (X 280-365, Y 225-250), 2.5 mm apart: 32 a row, 9 rows. */
    static final double X0 = 282.5, Y0 = 227.5, PITCH = 2.5;
    int spot;

    double[] nextSpot() {
        int i = spot++;
        assertTrue(i < 32 * 9, "out of blank");
        return new double[] {X0 + PITCH * (i % 32), Y0 + PITCH * (i / 32)};
    }

    /** A preset of paste-tips.json over the SIM's paste, as il_sim_scene.py --paste-by-tip KEY:preset=NAME lays it. */
    static SimScene.Paste preset(String name) throws Exception {
        String text = new String(Files.readAllBytes(new File("src/test/sim/paste-tips.json").toPath()),
                StandardCharsets.UTF_8);
        JsonObject p = new JsonParser().parse(text).getAsJsonObject().getAsJsonObject("presets")
                .getAsJsonObject(name);
        return SimScene.apply(p, SimPasteTest.scene().paste.copy());
    }

    /** The preset's scene; without noise (cv 0) the law's own mass, mean, swing and gap response, exactly. */
    static SimScene scene(String name, boolean noise) throws Exception {
        SimScene s = SimPasteTest.scene();
        s.paste = preset(name);
        SimScene.check(s.paste, name + ": ");
        s.paste.newPhaseOnMotorOff = false; // the check pins start angles itself (the jump is SimPasteTest's)
        if (!noise) {
            s.paste.cv = 0;
        }
        return s;
    }

    /** A SimPaste of the scene, without an engine (another test class may leave DispenseService installed). */
    static SimPaste paste(SimScene s) {
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        p.engineAims(() -> null);
        return p;
    }

    /** A pressurized needle at gap 0.20, its tip primed in the air (the preset's prime turns give nothing), past the
     * power-up's empty dots (every connection boots) and the transient after the idle: the steady state. */
    SimNeedle ready(SimScene s, SimPaste p, long net) {
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.gapMm = 0.20;
        n.pressurize();
        n.dotAt(200, 240, SimPasteTest.BLANK_Z + 20, SimPasteTest.BLANK_Z + 21, Math.round(s.paste.primeTurns * TURN));
        assertTrue(p.deposits(null).isEmpty(), "priming lays nothing");
        for (int i = 0; i < s.paste.emptyDotsAfterPowerCycle + WARM_UP_DOTS; i++) {
            dot(s, p, n, net, null, null);
        }
        return n;
    }

    /** A dot of {@code net} steps at the next spot, its physical start angle pinned (deg; null: as it comes); the
     * new deposit, or null. {@code landX} gets its landing error in X. */
    SimPaste.Deposit dot(SimScene s, SimPaste p, SimNeedle n, long net, Double startDeg, List<Double> landX) {
        double[] at = nextSpot();
        if (startDeg != null) {
            s.paste.offsetSteps = (int) Math.floorMod(Math.round(startDeg / 360 * TURN) - p.count(), TURN);
        }
        int before = p.deposits(null).size();
        n.dot(at[0], at[1], SimPasteTest.BLANK_Z, net);
        n.send("G4 P500"); // the cure's pause before travel; keeps the feed demand low too
        List<SimPaste.Deposit> d = p.deposits(null);
        SimPaste.Deposit laid = d.size() > before ? d.get(d.size() - 1) : null;
        if (laid != null && landX != null) {
            landX.add(laid.x - at[0]);
        }
        return laid;
    }

    /** {@code dots} consecutive dots as the bench lays them (tools/paste.py: one dot after the other, the tip bead
     * carrying paste between them): the run's first start pinned (deg), THROWAWAYS dots, then the ones returned. */
    List<SimPaste.Deposit> run(SimScene s, SimPaste p, SimNeedle n, long net, double startDeg, int dots,
            List<Double> landX) {
        dot(s, p, n, net, startDeg, null);
        for (int i = 1; i < THROWAWAYS; i++) {
            dot(s, p, n, net, null, null);
        }
        List<SimPaste.Deposit> laid = new ArrayList<>();
        for (int i = 0; i < dots; i++) {
            SimPaste.Deposit d = dot(s, p, n, net, null, landX);
            assertTrue(d != null, "every dot lands at gap " + n.gapMm);
            laid.add(d);
        }
        return laid;
    }

    /** The physical angle (deg) the deposit's first turn began at. */
    static double startDeg(SimScene s, SimPaste.Deposit d) {
        return Math.floorMod(d.startCount + s.paste.offsetSteps, TURN) * 360.0 / TURN;
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
    }

    static double sd(List<Double> v) {
        double m = mean(v);
        return Math.sqrt(v.stream().mapToDouble(a -> (a - m) * (a - m)).sum() / (v.size() - 1));
    }

    static double cv(List<Double> v) {
        return sd(v) / mean(v);
    }

    static void within(double value, double reference, double share, String what) {
        assertTrue(Math.abs(value - reference) <= share * reference,
                String.format("%s: %.4f, reference %.4f ±%.0f %%", what, value, reference, share * 100));
    }

    /**
     * §3's A as tools/lumen.py fits it: {@code f(phi) = 1 + A cos(phi - peak)} is the flow per degree and dot area the
     * dose proxy, so a half turn from phi0 lays {@code 1 - (2A/pi) sin(phi0 - peak)} of the mean: A is the first
     * harmonic of (start angle, area) over the mean, times pi/2.
     */
    static double halfTurnSwing(List<double[]> angleArea) {
        double n = angleArea.size(), a0 = 0, c = 0, sn = 0;
        for (double[] p : angleArea) {
            a0 += p[1] / n;
            c += 2 * p[1] * Math.cos(Math.toRadians(p[0])) / n;
            sn += 2 * p[1] * Math.sin(Math.toRadians(p[0])) / n;
        }
        return Math.hypot(c, sn) / a0 * Math.PI / 2;
    }

    /**
     * The area CV and the landing SD of runs of 40 dots of {@code net} steps from the balanced phase (the peak), each
     * the mean over SEEDS noise seeds: one seed's CV spreads about ±1 point, enough to hide a cv far outside the
     * band or to fail a right one.
     */
    double[] noisy(String preset, long net) throws Exception {
        double cv = 0;
        double sd = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            SimScene s = scene(preset, true);
            s.paste.noiseSeed = seed;
            spot = 0; // a new scene, a new store: its spots are bare
            SimPaste p = paste(s);
            SimNeedle n = ready(s, p, net);
            List<Double> areas = new ArrayList<>();
            List<Double> landX = new ArrayList<>();
            for (SimPaste.Deposit d : run(s, p, n, net, s.paste.ripplePeakDeg, 40, landX)) {
                areas.add(d.areaMm2());
            }
            cv += cv(areas) / SEEDS;
            sd += sd(landX) / SEEDS;
        }
        return new double[] {cv, sd};
    }

    @Test
    public void the22gHalfTurnDotsMatchTheReference() throws Exception {
        // §1-3 on the law: 8 runs of consecutive half turns, first starts 22.5° apart: 16 start angles, 2 dots each
        SimScene q = scene("22g-bent", false);
        SimPaste p = paste(q);
        SimNeedle n = ready(q, p, TURN / 2);
        List<Double> areas = new ArrayList<>();
        List<Double> mg = new ArrayList<>();
        List<double[]> angleArea = new ArrayList<>();
        for (int k = 0; k < 8; k++) {
            for (SimPaste.Deposit d : run(q, p, n, TURN / 2, k * 22.5, 4, null)) {
                areas.add(d.areaMm2());
                mg.add(d.mg);
                angleArea.add(new double[] {startDeg(q, d), d.areaMm2()});
            }
        }
        double a = halfTurnSwing(angleArea);
        // §2 and §7 with the noise: the CV at the balanced phase (a half turn from the peak: both halves equal)
        double[] noisy = noisy("22g-bent", TURN / 2);
        double c = noisy[0];
        System.out.printf("SIM 22G: %.3f mg/turn, mean area %.3f mm2, swing A %.3f; CV %.2f %% (balanced), "
                + "landing SD %.4f mm (means over %d seeds)%n", 2 * mean(mg), mean(areas), a, 100 * c, noisy[1],
                SEEDS);
        within(2 * mean(mg), 2.11, 0.10, "22G paste per whole turn, mg (§1: 169 mg / 160 half turns)");
        within(mean(areas), 0.788, 0.15, "22G half-turn mean area vs board 3 (§2)");
        within(mean(areas), 0.747, 0.15, "22G half-turn mean area vs p29 g0 (§2)");
        assertTrue(a >= 0.56 && a <= 0.60, "22G area swing A of tools/lumen.py's f(phi) (§3: 0.56-0.60): " + a);
        assertTrue(c >= 0.5 * 0.075 && c <= 1.5 * 0.075, "22G CV at the balanced phase: " + c + " (§2: 7.5 %)");
        within(noisy[1], 0.035, 0.02 / 0.035, "22G landing SD (§7: 0.03-0.04)");
    }

    @Test
    public void the25gWholeTurnDotsMatchTheReference() throws Exception {
        // §1, §2 and §6 on the law: whole turns at gaps 0.20, 0.10 and 0.30
        SimScene q = scene("25g-plastic", false);
        SimPaste p = paste(q);
        SimNeedle n = ready(q, p, TURN);
        List<Double> mg = new ArrayList<>();
        List<Double> byGap = new ArrayList<>();
        for (double gap : new double[] {0.20, 0.10, 0.30}) {
            n.gapMm = gap;
            List<Double> g = new ArrayList<>();
            for (int k = 0; k < 10; k++) {
                SimPaste.Deposit d = dot(q, p, n, TURN, null, null);
                assertTrue(d != null, "a whole turn lands at gap " + gap);
                g.add(d.areaMm2());
                mg.add(d.mg);
            }
            byGap.add(mean(g));
        }
        // the PM's condition (§2-3): a sub-turn contact is not modelled for the 25G: nothing laid, counted
        n.gapMm = 0.20;
        int refused = p.unmodelledContacts();
        assertNull(dot(q, p, n, TURN / 2, null, null), "a half turn lays nothing");
        assertEquals(refused + 1, p.unmodelledContacts());
        // §2 and §7 with the noise
        double[] noisy = noisy("25g-plastic", TURN);
        double c = noisy[0];
        System.out.printf("SIM 25G: %.3f mg/turn, mean area %.3f mm2 (gaps 0.20/0.10/0.30 %s); CV %.2f %%, "
                + "landing SD %.4f mm (means over %d seeds)%n", mean(mg), byGap.get(0), byGap, 100 * c, noisy[1],
                SEEDS);
        within(mean(mg), 1.95, 0.10, "25G paste per whole turn, mg (§1: +39 mg / 20 whole turns)");
        // §2's two rows on bare Cu, as the 22G's two: 1.29 (S0a) and 1.58-1.71 (the ladder)
        within(byGap.get(0), 1.29, 0.15, "25G whole-turn mean area vs S0a (§2)");
        within(byGap.get(0), 1.645, 0.15, "25G whole-turn mean area vs the ladder (§2)");
        assertTrue(Collections.max(byGap) / Collections.min(byGap) < 1.10,
                "25G areas alike over gaps 0.10-0.30 (§6): " + byGap);
        assertTrue(c >= 0.5 * 0.089 && c <= 1.5 * 0.089, "25G CV: " + c + " (§2: 8.9 %)");
        within(noisy[1], 0.045, 0.02 / 0.045, "25G landing SD (§7: 0.02-0.06)");
    }

    /** The values Process measured stand in the presets as measured (§1, §4-7, "What matters"): a mistyped key
     * would leave the SIM's base value, which the dots above need not show. */
    @Test
    public void thePresetsCarryTheMeasuredValues() throws Exception {
        SimScene.Paste a = preset("22g-bent");
        assertEquals(2.11, a.mRevMg, 1e-9, "22G mg per whole turn (§1)");
        assertTrue(a.primeTurns >= 70 && a.primeTurns <= 80, "22G prime turns (~70-80): " + a.primeTurns);
        assertEquals(12, a.emptyDotsAfterPowerCycle, "22G empty dots after a power cycle (§5)");
        assertTrue(a.maxGapMm >= 0.35, "22G dots land alike to gap 0.35 (§6): " + a.maxGapMm);
        assertEquals(0, a.threadShare, 0, "22G never strings at 18-22 psi (§6)");
        assertTrue(a.landingSdMm >= 0.03 && a.landingSdMm <= 0.04, "22G landing SD (§7): " + a.landingSdMm);
        assertTrue(a.newPhaseOnMotorOff, "22G: a new phase at every motor-off (§4)");
        SimScene.Paste b = preset("25g-plastic");
        assertEquals(1.95, b.mRevMg, 1e-9, "25G mg per whole turn (§1)");
        assertTrue(b.primeTurns >= 200 && b.primeTurns <= 258, "25G prime turns (~200-258): " + b.primeTurns);
        assertTrue(b.maxGapMm >= 0.30, "25G dots land alike to gap 0.30 (§6): " + b.maxGapMm);
        assertEquals(0, b.threadShare, 0, "25G: no threads with the cure (§6)");
        assertTrue(b.landingSdMm >= 0.02 && b.landingSdMm <= 0.06, "25G landing SD (§7): " + b.landingSdMm);
        assertTrue(b.newPhaseOnMotorOff, "25G: a new phase at every motor-off (§4)");
        assertTrue(b.wholeTurnsOnly, "25G: whole turns only (PM 11-04)");
    }
}
