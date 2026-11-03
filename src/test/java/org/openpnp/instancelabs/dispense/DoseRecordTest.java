package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openpnp.instancelabs.padprofile.LadderStats;

/**
 * Freshness of a dose record (profile calibration design §1.7): each rule alone, the partial-turn
 * rules only for optical and coupon records of partial turns, and a mass record's age counted from
 * its dots.
 */
public class DoseRecordTest {
    static final long NOW = 1_800_000_000_000L;
    static final long H = 3_600_000L;

    /** A record of this kind and dose with the conditions of cfg, made at createdAtMs. */
    static DoseRecord record(String kind, int steps, DispenseConfig cfg, long createdAtMs) {
        DoseRecord r = new DoseRecord();
        r.kind = kind;
        r.steps = steps;
        r.n = DoseRecord.COUPON.equals(kind) ? 68 : 28;
        r.createdAtMs = createdAtMs;
        r.pressurePsi = cfg.profile.pressurePsi;
        r.doseFp = cfg.profile.doseFingerprint();
        r.tipKey = cfg.tip.key;
        r.pasteKey = cfg.paste.key;
        r.regulatorPsi = cfg.profile.pressurePsi;
        r.regulatorAtMs = createdAtMs;
        r.method = "auto";
        if (DoseRecord.COUPON.equals(kind)) {
            r.couponPlace = "coupon1";
        }
        return r;
    }

    /** The reading pair of a coupon record (conditions copied from it, as the job does). */
    static DoseRecord mass(DoseRecord coupon, double totalMg) {
        DoseRecord m = new DoseRecord();
        m.kind = DoseRecord.MASS;
        m.steps = coupon.steps;
        m.n = coupon.n;
        m.couponRecordId = coupon.id;
        m.fullMg = 4100 + totalMg;
        m.emptyMg = 4100.0;
        m.totalMg = m.fullMg - m.emptyMg;
        m.mgPerDot = m.totalMg / m.n;
        m.mgPerStep = m.mgPerDot / m.steps;
        m.mgSe = 1.4 / m.n;
        m.weighDelayS = 600;
        m.pressurePsi = coupon.pressurePsi;
        m.doseFp = coupon.doseFp;
        m.tipKey = coupon.tipKey;
        m.pasteKey = coupon.pasteKey;
        m.regulatorPsi = coupon.regulatorPsi;
        m.regulatorAtMs = coupon.regulatorAtMs;
        m.method = "typed";
        return m;
    }

    /** Needle changed 6 h ago, paste loaded 5 h ago, controller connected 4 h ago, start angle set 3 h ago. */
    static DispenseConfig setup() {
        DispenseConfig c = new DispenseConfig();
        c.needleChangedAtMs = NOW - 6 * H;
        c.paste.loadedAtMs = NOW - 5 * H;
        c.profile.startAngleSetAtMs = NOW - 3 * H;
        return c;
    }

    static final DoseRecord.Epochs MACHINE = new DoseRecord.Epochs(NOW - 4 * H, false);

    @Test
    public void aRecordOfThisSetupIsFresh() {
        DispenseConfig c = setup();
        for (String kind : new String[] {DoseRecord.OPTICAL, DoseRecord.COUPON, DoseRecord.MASS}) {
            for (int steps : new int[] {400, 800, 1600}) {
                DoseRecord r = record(kind, steps, c, NOW - H);
                assertNull(r.whyStale(NOW, c, MACHINE), kind + " " + steps);
                assertTrue(r.isFresh(NOW, c, MACHINE));
            }
        }
    }

    @Test
    public void simulatorRecordsNeverCountOnTheMachineAndTheOtherWayRound() {
        DispenseConfig c = setup();
        DoseRecord r = record(DoseRecord.OPTICAL, 800, c, NOW - H);
        r.simulated = true;
        assertEquals("made in the simulator", r.whyStale(NOW, c, MACHINE));
        assertNull(r.whyStale(NOW, c, new DoseRecord.Epochs(NOW - 4 * H, true)));
        r.simulated = false;
        assertEquals("made on the machine, not in this simulator",
                r.whyStale(NOW, c, new DoseRecord.Epochs(NOW - 4 * H, true)));
    }

    @Test
    public void anotherRecipeOrRegulatorReading() {
        DispenseConfig c = setup();
        DoseRecord r = record(DoseRecord.OPTICAL, 800, c, NOW - H);
        c.profile.dwellMs = 300;
        assertEquals("made with another recipe (dwell 250 -> 300)", r.whyStale(NOW, c, MACHINE));
        c.profile.gapMm = 0.12;
        assertEquals("made with another recipe (dwell 250 -> 300, gap 0.1 -> 0.12)", r.whyStale(NOW, c, MACHINE));
        // the net turn, start angle and purge count are not part of the dose recipe
        DispenseConfig d = setup();
        d.profile.netStepsPerDot = 1600;
        d.profile.startAngleDeg = 118;
        d.profile.purgeDots = 6;
        d.profile.name = "other";
        assertNull(r.whyStale(NOW, d, MACHINE));
        // the regulator reading attested with the record must be the profile's pressure
        r.regulatorPsi = null;
        assertEquals("made without a regulator reading", r.whyStale(NOW, d, MACHINE));
        r.regulatorPsi = 17.5;
        assertEquals("made with the regulator at 17.5 psi; the profile asks for 18 psi", r.whyStale(NOW, d, MACHINE));
    }

    @Test
    public void anotherTipOrANeedleChange() {
        DispenseConfig c = setup();
        DoseRecord r = record(DoseRecord.MASS, 800, c, NOW - H);
        r.tipKey = "ad25g";
        assertEquals("made with another tip (ad25g); the tip now is the 22G tip (ad22tta-b)",
                r.whyStale(NOW, c, MACHINE));
        r.tipKey = c.tip.key;
        c.needleChangedAtMs = NOW - H / 2;
        assertEquals("made before the needle was changed", r.whyStale(NOW, c, MACHINE));
        c.needleChangedAtMs = null;
        assertNull(r.whyStale(NOW, c, MACHINE));
    }

    @Test
    public void anotherPasteOrNewPasteLoaded() {
        DispenseConfig c = setup();
        DoseRecord r = record(DoseRecord.MASS, 800, c, NOW - H);
        r.pasteKey = "sac305-t4";
        assertEquals("made with another paste (sac305-t4; the paste now is gc50-t5)", r.whyStale(NOW, c, MACHINE));
        r.pasteKey = c.paste.key;
        c.paste.loadedAtMs = NOW - H / 2;
        assertEquals("made before the new paste was loaded", r.whyStale(NOW, c, MACHINE));
    }

    /** Decision 9: doseRecordMaxAgeH, default 8 h, a setting. */
    @Test
    public void olderThanTheSetting() {
        DispenseConfig c = setup();
        c.needleChangedAtMs = null;
        c.paste.loadedAtMs = null;
        c.profile.startAngleSetAtMs = null;
        assertEquals(8, c.padCalibration.doseRecordMaxAgeH, 0);
        DoseRecord r = record(DoseRecord.MASS, 800, c, NOW - 8 * H);
        assertNull(r.whyStale(NOW, c, MACHINE), "8 h exactly still counts");
        r.createdAtMs = NOW - 8 * H - 1;
        assertEquals("older than 8 h (made 8.0 h ago)", r.whyStale(NOW, c, MACHINE));
        c.padCalibration.doseRecordMaxAgeH = 12;
        assertNull(r.whyStale(NOW, c, MACHINE));
        c.padCalibration.doseRecordMaxAgeH = 1.5;
        assertEquals("older than 1.5 h (made 8.0 h ago)", r.whyStale(NOW, c, MACHINE));
    }

    /** A mass row is typed after its dots were laid: its age, needle and paste rules count from the dots. */
    @Test
    public void aMassRecordCountsFromItsDots() {
        DispenseConfig c = setup();
        c.needleChangedAtMs = null;
        c.paste.loadedAtMs = null;
        DoseRecord m = record(DoseRecord.MASS, 800, c, NOW - 7 * H - H / 2);
        m.weighDelayS = 3600;
        assertEquals(NOW - 8 * H - H / 2, m.dispensedAtMs());
        assertEquals("older than 8 h (made 8.5 h ago)", m.whyStale(NOW, c, MACHINE));
        c.padCalibration.doseRecordMaxAgeH = 12;
        assertNull(m.whyStale(NOW, c, MACHINE));
        c.needleChangedAtMs = NOW - 8 * H;
        assertEquals("made before the needle was changed", m.whyStale(NOW, c, MACHINE),
                "the needle changed between the dots and the reading");
        // optical and coupon records were made when their dots were laid
        DoseRecord o = record(DoseRecord.OPTICAL, 800, c, NOW - H);
        o.weighDelayS = 36000;
        assertEquals(NOW - H, o.dispensedAtMs());
    }

    /** Per-phase results of a partial turn belong to the start angle of this controller power-up. */
    @Test
    public void partialTurnOpticalAndCouponRecordsNeedTheSameScrewAngle() {
        for (String kind : new String[] {DoseRecord.OPTICAL, DoseRecord.COUPON}) {
            for (int steps : new int[] {400, 800}) {
                DispenseConfig c = setup();
                DoseRecord r = record(kind, steps, c, NOW - H);
                DoseRecord.Epochs reconnected = new DoseRecord.Epochs(NOW - H / 2, false);
                assertEquals("made before the controller last connected: the screw angle may have moved",
                        r.whyStale(NOW, c, reconnected), kind + " " + steps);
                assertNull(r.whyStale(NOW, c, new DoseRecord.Epochs(null, false)), "connect time unknown");
                c.profile.startAngleSetAtMs = NOW - H / 2;
                assertEquals("made before the screw start angle was set again", r.whyStale(NOW, c, MACHINE));
                c.profile.startAngleSetAtMs = null;
                assertEquals("the screw start angle has never been set", r.whyStale(NOW, c, MACHINE));
            }
        }
    }

    /** Mass records (whole revolutions of dots) and records of whole turns survive a reconnect. */
    @Test
    public void massRecordsAndWholeTurnsSurviveAReconnect() {
        DispenseConfig c = setup();
        c.profile.startAngleSetAtMs = null;
        DoseRecord.Epochs reconnected = new DoseRecord.Epochs(NOW - H / 2, false);
        for (int steps : new int[] {400, 800, 1600}) {
            assertNull(record(DoseRecord.MASS, steps, c, NOW - H).whyStale(NOW, c, reconnected), "mass " + steps);
        }
        assertNull(record(DoseRecord.OPTICAL, 1600, c, NOW - H).whyStale(NOW, c, reconnected));
        assertNull(record(DoseRecord.COUPON, 1600, c, NOW - H).whyStale(NOW, c, reconnected));
        assertFalse(record(DoseRecord.OPTICAL, 1600, c, NOW).isPartialTurn(1600));
        assertTrue(record(DoseRecord.OPTICAL, 400, c, NOW).isPartialTurn(1600));
    }

    @Test
    public void aMassRowWithoutReadingsIsASkippedWeighing() {
        DispenseConfig c = setup();
        DoseRecord m = mass(record(DoseRecord.COUPON, 800, c, NOW - H), 72);
        assertTrue(m.hasReading());
        m.fullMg = null;
        m.emptyMg = null;
        m.totalMg = null;
        assertFalse(m.hasReading());
        assertFalse(record(DoseRecord.OPTICAL, 800, c, NOW).hasReading());
    }

    /**
     * Review 2: §7.5 divides a coupon's paste by the dots laid (every turn pushes its paste out; a dot the camera
     * missed left it on the tip for the next one), not by the dots the camera found.
     */
    @Test
    public void aReadingIsPerDotLaid() {
        DispenseConfig c = setup();
        DoseRecord coupon = record(DoseRecord.COUPON, 800, c, NOW - H);
        coupon.id = 1;
        coupon.found = 60;
        DoseRecord m = mass(coupon, 72);
        m.id = 2;
        List<LadderStats.MassReading> l = DoseRecords.readings(Collections.singletonList(m), Arrays.asList(coupon,
                m));
        assertEquals(1, l.size());
        assertEquals(68, l.get(0).dots);
        assertEquals(72.0 / 68, l.get(0).mgPerDot(), 1e-12);
    }
}
