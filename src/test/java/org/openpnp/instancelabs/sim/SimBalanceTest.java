package org.openpnp.instancelabs.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.dispense.Balance;
import org.openpnp.instancelabs.dispense.ManualBalance;
import org.openpnp.model.Configuration;

/**
 * The balance interface (§10): the manual balance asks the operator; the SIM balance (§11.6) rounds
 * the coupon and its SIM paste to its resolution, settles after the load, and its emulated human
 * weighs, wipes and weighs again. Only on the confirmed simulator.
 */
public class SimBalanceTest {
    final long[] nowMs = {1_000_000};
    final boolean[] simulated = {true};

    SimBalance balance(SimScene s, SimPaste.Store store) {
        return new SimBalance(() -> s, store, () -> simulated[0], () -> nowMs[0], (ms) -> nowMs[0] += ms);
    }

    /** The §11.3 scene with paste laid on coupon 1 (24 half-turn dots, the exact law: 27.6 mg). */
    static SimScene sceneWithPaste(String mode) {
        SimScene s = SimPasteTest.exact();
        s.paste.ripple = 0;
        s.balance.mode = mode;
        SimPaste p = new SimPaste(() -> s, SimPaste.Store.forScene(s), () -> 0L, () -> 0.0);
        SimNeedle n = new SimNeedle(p, s.paste.n2OffsetMm);
        n.pressurize();
        n.row(334, 50, 1.4, 24, PadcalScenes.COUPON_Z, 800);
        n.vent();
        assertEquals(24 * 1.15, p.couponMassMg("coupon1"), 1e-9);
        return s;
    }

    @Test
    public void theManualBalanceIsReadByTheOperator() {
        Balance b = new ManualBalance();
        assertEquals("manual", b.kind());
        assertFalse(b.isConnected());
        assertEquals(1.0, b.resolutionMg(), 0);
        assertThrows(UnsupportedOperationException.class, () -> b.read(1000));
    }

    @Test
    public void readingsAreTheCouponAndItsPasteRoundedToTheResolution() throws Exception {
        SimScene s = sceneWithPaste("display");
        s.balance.noiseMg = 0;
        SimBalance b = balance(s, SimPaste.Store.forScene(s));
        assertEquals("sim", b.kind());
        assertFalse(b.isConnected(), "display: the operator types what it shows");
        assertEquals(1, b.resolutionMg(), 0);
        assertNull(b.loaded());
        assertEquals(0, b.read(0).mg, 0, "an empty pan");
        b.load("coupon1");
        assertEquals("coupon1", b.loaded());
        assertEquals(4100 + 27.6, b.massOnPanMg(), 1e-9);
        Balance.Reading r = b.read(10_000);
        assertEquals(4128, r.mg, 0, "4127.6 mg on a 1 mg balance");
        assertTrue(r.stable);
        s.balance.resolutionMg = 10;
        assertEquals(4130, b.display().mg, 0);
        s.balance.resolutionMg = 0.1;
        assertEquals(4127.6, b.display().mg, 1e-9);
        b.load("coupon2");
        assertEquals(4060, b.read(10_000).mg, 1e-9, "the bare coupon 2");
        assertThrows(IllegalArgumentException.class, () -> b.load("coupon9"));
    }

    @Test
    public void aReadingSettlesAfterTheLoad() throws Exception {
        SimScene s = sceneWithPaste("connected");
        SimBalance b = balance(s, SimPaste.Store.forScene(s));
        assertTrue(b.isConnected());
        b.load("coupon1");
        long loadedAt = nowMs[0];
        assertFalse(b.display().stable, "right after the load");
        assertFalse(b.read(500).stable, "not settled within 0.5 s");
        assertEquals(loadedAt + 500, nowMs[0], "read waited its timeout");
        Balance.Reading r = b.read(10_000);
        assertTrue(r.stable);
        assertEquals(loadedAt + 2000, nowMs[0], "settleS 2: read waits for it, no longer");
        assertEquals(r.atMs, nowMs[0]);
        assertEquals(4128, r.mg, 2, "noise 0.4 mg");
    }

    @Test
    public void noiseIsSeededAndSmall() throws Exception {
        SimScene s = sceneWithPaste("display");
        s.balance.resolutionMg = 0.001;
        SimBalance b = balance(s, SimPaste.Store.forScene(s));
        b.load("coupon1");
        nowMs[0] += 5000;
        double sum = 0, sum2 = 0;
        int n = 400;
        for (int i = 0; i < n; i++) {
            double e = b.display().mg - 4127.6;
            sum += e;
            sum2 += e * e;
        }
        double sd = Math.sqrt(sum2 / n - (sum / n) * (sum / n));
        assertEquals(0.4, sd, 0.06, "N(0, noiseMg)");
        assertEquals(0, sum / n, 0.08);
    }

    @Test
    public void theEmulatedHumanWeighsWipesAndWeighsAgain() throws Exception {
        SimScene s = sceneWithPaste("connected");
        s.balance.noiseMg = 0;
        SimPaste.Store store = SimPaste.Store.forScene(s);
        SimBalance b = balance(s, store);
        long t0 = nowMs[0];
        Balance.Reading[] r = b.weighWipeWeigh("coupon1", 10_000);
        assertEquals(4128, r[0].mg, 0, "with paste");
        assertEquals(4100, r[1].mg, 0, "wiped");
        assertTrue(r[0].stable && r[1].stable);
        assertEquals(t0 + 4000, nowMs[0], "two settles");
        assertEquals(0, store.massMg("coupon1"), 0, "the human wiped the coupon");
        assertNull(b.loaded(), "and put it back on its place");
        assertEquals(24 * 1.15, r[0].mg - r[1].mg, 0.5 + 1e-9, "the difference is the paste, to the resolution");
    }

    @Test
    public void onlyOnTheConfirmedSimulator() throws Exception {
        SimScene s = sceneWithPaste("display");
        SimBalance b = balance(s, SimPaste.Store.forScene(s));
        b.load("coupon1");
        simulated[0] = false;
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> b.read(0));
        assertTrue(e.getMessage().contains("not confirmed"), e.getMessage());
        assertThrows(IllegalStateException.class, b::display);
        s.balance = null;
        simulated[0] = true;
        assertThrows(IllegalStateException.class, b::display, "the scene's balance block went away");
    }

    @Test
    public void noSimBalanceForAMachineThatIsNotTheSimulator() throws Exception {
        File dir = java.nio.file.Files.createTempDirectory("sim-balance").toFile();
        Configuration.initialize(dir);
        Configuration.get().load();
        assertNull(SimBalance.forConfiguration(null));
        assertNull(SimBalance.forConfiguration(Configuration.get()), "no confirmed simulator, no scene");
    }

    @Test
    public void thePanIsTheScenesNotTheInstances() throws Exception {
        SimScene s = sceneWithPaste("display");
        SimPaste.Store store = SimPaste.Store.forScene(s);
        balance(s, store).load("coupon2");
        assertEquals("coupon2", balance(s, store).loaded(), "a second request sees the coupon the first one loaded");
    }
}
