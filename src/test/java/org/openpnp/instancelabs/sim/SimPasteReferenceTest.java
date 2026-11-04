package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * INF-06's acceptance: the SIM's per-tip pastes ({@code src/test/sim/paste-tips.json}) lay dots that match Process's
 * measured reference ({@code docs/research/paste-model-reference-per-tip.md}, 11-04) within its CI tolerance: mean
 * area ±15 % (phase-averaged: a board's 298°/118° pairs average the phase, Process 05:0x), CV up to 1.5× the measured
 * one (at the balanced phase, where both halves of a turn are equal), landing SD ±0.02 mm per axis.
 */
public class SimPasteReferenceTest {
    static final long TURN = SimPaste.STEPS_PER_REV;
    static final int WARM_UP_DOTS = 6; // the transient after the idle (oozeFirst, refillDip): not the steady state
    /** Spots on the bench blank (X 280-365, Y 225-250), 2.5 mm apart: 32 a row, 9 rows. */
    static final double X0 = 282.5, Y0 = 227.5, PITCH = 2.5;
    int spot;

    double[] nextSpot() {
        int i = spot++;
        assertTrue(i < 32 * 9, "out of blank");
        return new double[] {X0 + PITCH * (i % 32), Y0 + PITCH * (i / 32)};
    }

    static SimScene scene(String preset) throws Exception {
        String text = new String(Files.readAllBytes(new File("src/test/sim/paste-tips.json").toPath()),
                StandardCharsets.UTF_8);
        JsonObject p = new JsonParser().parse(text).getAsJsonObject().getAsJsonObject("presets")
                .getAsJsonObject(preset);
        SimScene s = SimPasteTest.scene();
        s.paste = SimScene.apply(p, s.paste.copy());
        SimScene.check(s.paste, preset + ": ");
        return s;
    }

    static SimPaste paste(SimScene s) {
        return new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
    }

    /** A pressurized needle at the gap, its tip primed in the air (the preset's prime turns give nothing). */
    static SimNeedle primed(SimScene s, SimPaste p, double gapMm) {
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.gapMm = gapMm;
        n.pressurize();
        n.dotAt(200, 240, SimPasteTest.BLANK_Z + 20, SimPasteTest.BLANK_Z + 21, Math.round(s.paste.primeTurns * TURN));
        assertTrue(p.deposits(null).isEmpty(), "priming lays nothing");
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

    static double area(SimPaste.Deposit d) {
        return Math.PI * d.aMm * d.bMm;
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().getAsDouble();
    }

    static double cv(List<Double> v) {
        double m = mean(v);
        return Math.sqrt(v.stream().mapToDouble(a -> (a - m) * (a - m)).sum() / (v.size() - 1)) / m;
    }

    static double sd(List<Double> v) {
        double m = mean(v);
        return Math.sqrt(v.stream().mapToDouble(a -> (a - m) * (a - m)).sum() / (v.size() - 1));
    }

    static void within(double value, double reference, double share, String what) {
        assertTrue(Math.abs(value - reference) <= share * reference,
                String.format("%s: %.4f, reference %.4f ±%.0f %%", what, value, reference, share * 100));
    }

    /** The area swing A of {@code 1 + A cos(phi - peak)} fitted to (start angle, area): first harmonic over mean. */
    static double swing(List<double[]> angleArea) {
        double n = angleArea.size(), a0 = 0, c = 0, sn = 0;
        for (double[] p : angleArea) {
            a0 += p[1] / n;
            c += 2 * p[1] * Math.cos(Math.toRadians(p[0])) / n;
            sn += 2 * p[1] * Math.sin(Math.toRadians(p[0])) / n;
        }
        return Math.hypot(c, sn) / a0;
    }

    @Test
    public void the22gHalfTurnDotsMatchTheReference() throws Exception {
        SimScene s = scene("22g-bent");
        SimPaste p = paste(s);
        SimNeedle n = primed(s, p, 0.20);
        for (int i = 0; i < WARM_UP_DOTS; i++) {
            dot(s, p, n, TURN / 2, null, null);
        }
        // §2-3: 16 start angles evenly, 4 dots each: the phase-averaged mean and the area swing
        List<Double> areas = new ArrayList<>();
        List<double[]> angleArea = new ArrayList<>();
        List<Double> landX = new ArrayList<>();
        for (int k = 0; k < 64; k++) {
            double start = (k % 16) * 22.5;
            SimPaste.Deposit d = dot(s, p, n, TURN / 2, start, landX);
            assertTrue(d != null, "every half turn lands at the 0.20 gap");
            areas.add(area(d));
            angleArea.add(new double[] {start, area(d)});
        }
        within(mean(areas), 0.788, 0.15, "22G half-turn mean area vs board 3 (§2)");
        within(mean(areas), 0.747, 0.15, "22G half-turn mean area vs p29 g0 (§2)");
        within(swing(angleArea), 0.58, 0.15, "22G half-turn area swing A (§3: 0.56-0.60)");
        // §2: the CV at the balanced phase (each half turn starts where both halves are equal)
        List<Double> balanced = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            balanced.add(area(dot(s, p, n, TURN / 2, s.paste.ripplePeakDeg + (k % 2) * 180.0, landX)));
        }
        double c = cv(balanced);
        System.out.printf("SIM 22G: mean area %.3f mm2, swing A %.3f, CV %.1f %% (balanced), landing SD %.3f mm%n",
                mean(areas), swing(angleArea), 100 * c, sd(landX));
        assertTrue(c >= 0.5 * 0.075 && c <= 1.5 * 0.075, "22G CV at the balanced phase: " + c + " (§2: 7.5 %)");
        // §7: the landing scatter per axis
        within(sd(landX), 0.035, 0.02 / 0.035,
                "22G landing SD (§7: 0.03-0.04)");
    }

    @Test
    public void the25gWholeTurnDotsMatchTheReference() throws Exception {
        SimScene s = scene("25g-plastic");
        SimPaste p = paste(s);
        SimNeedle n = primed(s, p, 0.20);
        for (int i = 0; i < WARM_UP_DOTS; i++) {
            dot(s, p, n, TURN, null, null);
        }
        // §2: whole turns on bare Cu at gap 0.20; the rows say 1.29 (S0a) and 1.58-1.71 (ladder): their midpoint ±15 %
        List<Double> areas = new ArrayList<>();
        List<Double> landX = new ArrayList<>();
        for (int k = 0; k < 40; k++) {
            areas.add(area(dot(s, p, n, TURN, null, landX)));
        }
        double c = cv(areas);
        System.out.printf("SIM 25G: mean area %.3f mm2, CV %.1f %%, landing SD %.3f mm%n", mean(areas), 100 * c,
                sd(landX));
        within(mean(areas), (1.29 + 1.645) / 2, 0.15, "25G whole-turn mean area (§2)");
        assertTrue(c >= 0.5 * 0.089 && c <= 1.5 * 0.089, "25G CV: " + c + " (§2: 8.9 %)");
        within(sd(landX), 0.045, 0.02 / 0.045,
                "25G landing SD (§7: 0.02-0.06)");
        // §6: the same dots over gaps 0.10-0.30
        List<Double> byGap = new ArrayList<>();
        for (double gap : new double[] {0.10, 0.20, 0.30}) {
            n.gapMm = gap;
            List<Double> g = new ArrayList<>();
            for (int k = 0; k < 10; k++) {
                SimPaste.Deposit d = dot(s, p, n, TURN, null, null);
                assertTrue(d != null, "a whole turn lands at gap " + gap);
                g.add(area(d));
            }
            byGap.add(mean(g));
        }
        double lo = byGap.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
        double hi = byGap.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
        assertTrue(hi / lo < 1.10, "25G areas alike over gaps 0.10-0.30 (§6): " + byGap);
        // the PM's condition (§2-3): a sub-turn contact is not modelled for the 25G: nothing laid, counted
        n.gapMm = 0.20;
        int refused = p.unmodelledContacts();
        assertNull(dot(s, p, n, TURN / 2, null, null), "a half turn lays nothing");
        assertEquals(refused + 1, p.unmodelledContacts());
    }
}
