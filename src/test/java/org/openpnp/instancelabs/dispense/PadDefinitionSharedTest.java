package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.PadInstance;
import org.openpnp.instancelabs.padprofile.ProfileCheck;
import org.openpnp.instancelabs.padprofile.Tip;

/**
 * Tip setups (§22.2, §22.11 item 7, §22.8): what describes the pad is one definition for every setup. A pattern and
 * pad width changed under the 25G resolve a board's 0603 pads to the same type and amount with the 22G mounted; a
 * retire under the 25G retires the type for the 22G; the 22G's characterized version keeps its status, and the check
 * flags OUTSIDE_AREA only beyond its ratio.
 */
public class PadDefinitionSharedTest {
    static final String K25 = "25g-straight";

    @Test
    public void aPadEditUnderOneTipHoldsForEveryTip() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.insertTipSetupVersion(TipSetupsTest.straight25());
            TipSetupsTest.version(db, "chip-0603", Tip.SEED_KEY, PadTypeStatus.CHARACTERIZED, 800, 1,
                    t -> t.calAreaMm2 = 0.73);
            TipSetupsTest.version(db, "chip-0603", K25, PadTypeStatus.PROVISIONAL, 400, 2, t -> {
                t.padWMm = 0.85;
                t.packagePattern = t.packagePattern + "|MYCHIP";
            });
            PadTypeResolver r22 = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null);
            PadTypeResolver r25 = new PadTypeResolver(db.currentPadTypes(K25), null);
            for (String pkg : new String[] {"MYCHIP_X", "R_0603_1608Metric"}) {
                PadType a = r22.resolve(pkg, Double.NaN, Double.NaN, Double.NaN);
                PadType b = r25.resolve(pkg, Double.NaN, Double.NaN, Double.NaN);
                assertEquals("chip-0603", a.typeKey, pkg);
                assertEquals("chip-0603", b.typeKey, pkg);
                assertEquals(0.85, a.padWMm, 0);
                assertEquals(a.padWMm, b.padWMm, 0);
                double amountA = DotMath.targetVolumeMm3(DotMath.padAreaMm2(a.padWMm, a.padHMm, a.padRoundness), a.tMm,
                        a.k);
                double amountB = DotMath.targetVolumeMm3(DotMath.padAreaMm2(b.padWMm, b.padHMm, b.padRoundness), b.tMm,
                        b.k);
                assertEquals(amountA, amountB, 0, "the amount a pad needs is the pad's, whichever tip is on");
            }
            PadType a = db.currentPadType("chip-0603", Tip.SEED_KEY);
            assertEquals(PadTypeStatus.CHARACTERIZED, a.status, "the 22G keeps its calibration");
            assertEquals(2, a.version);
            assertEquals(3, (int) a.padVersion);
            assertNotEquals(r22.fingerprint(), new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null)
                    .withSetup(Tip.SEED_KEY, 1).fingerprint());
            // retired under the 25G: retired for the 22G as well
            TipSetupsTest.version(db, "chip-0603", K25, PadTypeStatus.PROVISIONAL, 400, 2, t -> {
                t.family = PadInstances.RETIRED_FAMILY;
                t.packagePattern = null;
            });
            PadType retired = db.currentPadType("chip-0603", Tip.SEED_KEY);
            assertEquals(PadInstances.RETIRED_FAMILY, retired.family);
            assertNull(retired.packagePattern);
            assertEquals(PadTypeStatus.CHARACTERIZED, retired.status, "a status of its own, the pad retired");
            PadType none = new PadTypeResolver(db.currentPadTypes(Tip.SEED_KEY), null).resolve("MYCHIP_X", Double.NaN,
                    Double.NaN, Double.NaN);
            assertTrue(none == null || !"chip-0603".equals(none.typeKey), String.valueOf(none));
            assertTrue(PadInstances.typeInfo(retired, null).retired);
        }
    }

    /** A two-pad part of the type's pad size. */
    static List<PadInstance> part(PadType t) {
        List<PadInstance> l = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            PadInstance p = new PadInstance("R1", "R_0603_1608Metric", Integer.toString(i + 1), 100 + i * 1.65, 80, 0,
                    t.padWMm, t.padHMm, t.padRoundness);
            p.padId = "R1." + (i + 1);
            p.typeKey = t.typeKey;
            p.typeVersion = t.version;
            p.typeStatus = t.status.dbName;
            p.typeRule = "pattern";
            l.add(p);
        }
        return l;
    }

    static ProfileCheck.Group check(DispenseDb db) throws Exception {
        List<PadType> types = db.currentPadTypes(Tip.SEED_KEY);
        PadType chip = TipSetupsTest.byKey(types, "chip-0603");
        DispenseConfig c = new DispenseConfig();
        ProfileCheck.Result r = ProfileCheck.of(PadInstances.inputs(part(chip), types, Collections.emptyMap(), c, null,
                false, Tip.g22()));
        return r.groups.isEmpty() ? null : r.groups.get(0);
    }

    static boolean notArea(ProfileCheck.Group g) {
        return g == null || !ProfileCheck.OUTSIDE_AREA.equals(g.outsideKind);
    }

    @Test
    public void theOtherTipsPlanIsLeftToTheCalibratedAreaRatio() throws Exception {
        try (DispenseDb db = DispenseDb.inMemory()) {
            db.insertTipSetupVersion(TipSetupsTest.straight25());
            TipSetupsTest.version(db, "chip-0603", Tip.SEED_KEY, PadTypeStatus.CHARACTERIZED, 800, 1,
                    t -> t.calAreaMm2 = 0.73);
            assertTrue(notArea(check(db)), "covered: the 0603 pads are what the 22G calibrated");
            // a pad edit within the ratio (0.85 x 0.95 = 0.81 mm², 1.11 x 0.73): still covered
            TipSetupsTest.version(db, "chip-0603", K25, PadTypeStatus.PROVISIONAL, 400, 2, t -> t.padWMm = 0.85);
            assertTrue(notArea(check(db)));
            // beyond it (1.20 x 0.95 = 1.14 mm², 1.56 x): the 22G's plan is outside what it was calibrated for
            TipSetupsTest.version(db, "chip-0603", K25, PadTypeStatus.PROVISIONAL, 400, 2, t -> t.padWMm = 1.20);
            ProfileCheck.Group g = check(db);
            assertEquals(ProfileCheck.OUTSIDE, g.reason);
            assertEquals(ProfileCheck.OUTSIDE_AREA, g.outsideKind);
            assertEquals(PadTypeStatus.CHARACTERIZED, db.currentPadType("chip-0603", Tip.SEED_KEY).status);
        }
    }
}
