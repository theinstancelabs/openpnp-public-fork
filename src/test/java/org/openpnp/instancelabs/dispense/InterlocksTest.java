package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

/** Pre-flight interlocks (plan §5.5; I9-I11 of the bench recipe 2026-10-28). */
public class InterlocksTest {
    /** A board run that passes: needle frame, purge row, camera, start angle set after the connect. */
    static Interlocks.Inputs ok() {
        Interlocks.Inputs in = new Interlocks.Inputs();
        DispenseConfig c = GcodeStreamTest.toyConfig();
        c.needleZOffsetSetAtMs = 2000L;
        c.needleChangedAtMs = 1000L;
        // needle frame: N1 at 5.5 and the needle 2.59 mm lower in N2's frame on the same surface
        c.needleAnchor = new DispenseConfig.NeedleAnchor(5.5, 2.91, null, null, 1500L);
        c.profile.startAngleSetAtMs = 3000L;
        in.cfg = c;
        in.machineEnabled = true;
        in.machineHomed = true;
        in.nozzleName = "N2";
        in.compatibleNozzleTips = Collections.emptyList();
        in.plan = GcodeStreamTest.toyPlan(c);
        in.estimatedPressurisedSeconds = 10;
        in.boardRun = true;
        in.purgeDots = c.profile.purgeDots;
        in.opticalCheckAvailable = true;
        in.controllerConnectedAtMs = 2500L;
        return in;
    }

    static boolean noted(InterlockReport r, String prefix) {
        return r.notes.stream().anyMatch(n -> n.startsWith(prefix));
    }

    @Test
    public void allClear() {
        InterlockReport r = Interlocks.check(ok());
        assertTrue(r.ok(), r.toString());
    }

    @Test
    public void i3PickNozzleRefused() {
        Interlocks.Inputs in = ok();
        in.compatibleNozzleTips = Arrays.asList("NT1", "NT2");
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("I3"));
        assertTrue(r.toString().contains("NT1"));
        in = ok();
        in.loadedNozzleTip = "NT1";
        assertTrue(Interlocks.check(in).has("I3"));
    }

    @Test
    public void i4NeedleTouchOffAfterChange() {
        Interlocks.Inputs in = ok();
        in.cfg.needleChangedAtMs = 3000L;
        assertTrue(Interlocks.check(in).has("I4"));
        in.cfg.needleZOffsetSetAtMs = null;
        in.cfg.needleChangedAtMs = null;
        assertTrue(Interlocks.check(in).has("I4"));
    }

    @Test
    public void i5PressureWindowHook() {
        Interlocks.Inputs in = ok();
        in.cfg.pressureInterlockEnabled = true;
        assertTrue(Interlocks.check(in).has("I5"), "enabled without sensor refuses");
        in.pressureSensor = () -> 25.0;
        assertTrue(Interlocks.check(in).has("I5"));
        in.pressureSensor = () -> 19.5;
        assertTrue(Interlocks.check(in).has("I5"), "above the 19 psi maximum (20 psi drools)");
        in.pressureSensor = () -> 18.0;
        assertFalse(Interlocks.check(in).has("I5"));
        in.cfg.pressureInterlockEnabled = false;
        in.pressureSensor = () -> 99.0;
        assertFalse(Interlocks.check(in).has("I5"), "disabled by default");
    }

    /** I6: the regulator is set by hand; the report says to what (a note, never a refusal). */
    @Test
    public void i6RegulatorNote() {
        Interlocks.Inputs in = ok();
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.notes.stream().anyMatch(n -> n.startsWith("I6: set the regulator to 18.0 psi")), r.notes
                .toString());
        in.cfg.profile.pressurePsi = 17.5;
        r = Interlocks.check(in);
        assertTrue(r.ok(), r.toString());
        assertTrue(r.notes.stream().anyMatch(n -> n.contains("17.5 psi")), r.notes.toString());
    }

    @Test
    public void i7EstimatedOnTime() {
        Interlocks.Inputs in = ok();
        in.estimatedPressurisedSeconds = in.cfg.maxContinuousOnTimeS + 1;
        assertTrue(Interlocks.check(in).has("I7"));
    }

    /** I9: the purge row must be set and have a free spot for every purge dot. */
    @Test
    public void i9PurgeRow() {
        Interlocks.Inputs in = ok();
        in.cfg.purgeRow.x0Mm = null;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("I9"), r.toString());
        assertTrue(r.toString().contains("purge row is not set"), r.toString());
        in = ok();
        in.cfg.purgeRow.usedSpots = 10; // 6 of 16 left, 8 needed
        r = Interlocks.check(in);
        assertTrue(r.has("I9"), r.toString());
        assertTrue(r.toString().contains("only 6 unused purge spot(s) left, 8 needed"), r.toString());
        in.cfg.purgeRow.usedSpots = 8; // exactly 8 left
        assertFalse(Interlocks.check(in).has("I9"));
        // a profile without purge dots: nothing to lay, a note
        in = ok();
        in.cfg.purgeRow.spots = 0;
        in.purgeDots = 0;
        r = Interlocks.check(in);
        assertFalse(r.has("I9"), r.toString());
        assertTrue(noted(r, "I9: no purge dots"), r.notes.toString());
    }

    /** I10: the clean-pad guard needs the top camera. */
    @Test
    public void i10CleanPadCheckNeedsACamera() {
        Interlocks.Inputs in = ok();
        in.opticalCheckAvailable = false;
        assertTrue(Interlocks.check(in).has("I10"));
        in.cfg.cleanPadCheck.enabled = false;
        InterlockReport r = Interlocks.check(in);
        assertFalse(r.has("I10"), r.toString());
        assertTrue(noted(r, "I10: clean-pad check disabled"), r.notes.toString());
    }

    /**
     * I11: the start angle of a partial turn is a calibration of the controller's power-up (the B
     * step count restarts there): set or confirmed after the last connect.
     */
    @Test
    public void i11StartAngleBelongsToTheCurrentPowerUp() {
        Interlocks.Inputs in = ok();
        in.cfg.profile.startAngleSetAtMs = null;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("I11"), r.toString());
        assertTrue(r.toString().contains("never been set"), r.toString());
        in.cfg.profile.startAngleSetAtMs = 2000L; // before the connect at 2500
        r = Interlocks.check(in);
        assertTrue(r.has("I11"), r.toString());
        assertTrue(r.toString().contains("(re)connected after the screw start angle was set"), r.toString());
        in.cfg.profile.startAngleSetAtMs = 2500L; // confirmed right at the connect: fine
        assertFalse(Interlocks.check(in).has("I11"));
        in.controllerConnectedAtMs = null; // connect time unknown: set at all is enough
        in.cfg.profile.startAngleSetAtMs = 1L;
        assertFalse(Interlocks.check(in).has("I11"));
        // a whole revolution per dot repeats the angle whatever it is
        in = ok();
        in.cfg.profile.netStepsPerDot = 1600;
        in.cfg.profile.startAngleSetAtMs = null;
        assertFalse(Interlocks.check(in).has("I11"), Interlocks.check(in).toString());
    }

    /** I9-I11 are board-run rules: calibration, shots and the hold do not lay a purge row. */
    @Test
    public void boardRulesOnlyForBoardRuns() {
        Interlocks.Inputs in = ok();
        in.boardRun = false;
        in.purgeDots = 0;
        in.cfg.purgeRow.x0Mm = null;
        in.opticalCheckAvailable = false;
        in.cfg.profile.startAngleSetAtMs = null;
        InterlockReport r = Interlocks.check(in);
        assertFalse(r.has("I9") || r.has("I10") || r.has("I11"), r.toString());
    }

    /** Review finding: a gate raised (or a pad type edited) after planning left the plan current and the run went on. */
    @Test
    public void padTypesChangedAfterPlanningMakeThePlanStale() {
        Interlocks.Inputs in = ok();
        assertFalse(Interlocks.check(in).has("PLAN"));
        // the gate raised above the plan's pad types (toy pads: provisional)
        in.cfg.minPadTypeStatus = PadTypeStatus.VALIDATED.dbName;
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("PLAN"), r.toString());
        assertTrue(r.toString().contains("gate changed after planning"), r.toString());
        // the pad type edited (a new version) or retired after planning
        in = ok();
        String key = in.plan.pads.get(0).typeKey;
        PadType now = new PadType();
        now.typeKey = key;
        now.version = in.plan.pads.get(0).typeVersion + 1;
        in.padTypesNow = new java.util.HashMap<>();
        in.padTypesNow.put(key, now);
        r = Interlocks.check(in);
        assertTrue(r.toString().contains("pad types changed after planning: " + key), r.toString());
        // the same versions: still current
        now.version = in.plan.pads.get(0).typeVersion;
        assertFalse(Interlocks.check(in).has("PLAN"), Interlocks.check(in).toString());
    }

    /**
     * Final review: a pad type created (or a pattern or package mapping changed) after planning can take the plan's
     * packages over while the plan's own types keep their versions; the plan read as current and a run went on.
     */
    @Test
    public void padTypeResolutionChangedAfterPlanningMakesThePlanStale() {
        Interlocks.Inputs in = ok();
        PadType chip = DispenseDb.seedPadTypes().get(1); // chip-0603, as the toy plan was resolved with
        chip.version = 1;
        assertEquals(new PadTypeResolver(Collections.singletonList(chip), null).fingerprint(), in.plan.padTypesFingerprint);
        in.padTypesFingerprintNow = in.plan.padTypesFingerprint;
        assertFalse(Interlocks.check(in).has("PLAN"), Interlocks.check(in).toString());
        // a newer type with a matching pattern now wins the plan's 0603 packages
        PadType hd = new PadType("custom-0603-hd", "Custom", "0603 HD", 0.8, 0.95, PadType.Layout.SINGLE, null, 1.0,
                "0603", null);
        hd.version = 1;
        PadTypeResolver now = new PadTypeResolver(Arrays.asList(chip, hd), null);
        assertEquals("custom-0603-hd", now.resolve("R_0603_1608Metric", Double.NaN, Double.NaN, Double.NaN).typeKey);
        in.padTypesFingerprintNow = now.fingerprint();
        InterlockReport r = Interlocks.check(in);
        assertTrue(r.has("PLAN"), r.toString());
        assertTrue(r.toString().contains("pad types or package mappings changed after planning"), r.toString());
        // a package mapping added after planning
        in.padTypesFingerprintNow = new PadTypeResolver(Collections.singletonList(chip),
                Collections.singletonMap("R_0603_1608Metric", "chip-0402")).fingerprint();
        assertTrue(Interlocks.check(in).has("PLAN"));
        // the resolver inputs now unknown (database not readable): not refused on this ground
        in.padTypesFingerprintNow = null;
        assertFalse(Interlocks.check(in).has("PLAN"));
    }

    @Test
    public void flaggedPadsAndMachineState() {
        Interlocks.Inputs in = ok();
        // a pad below the gate (its status too, else the plan reads as made under an older gate)
        in.plan.pads.get(0).flags.add(PadFlag.STATUS_BELOW_MIN);
        in.plan.pads.get(0).typeStatus = PadTypeStatus.NOT_STARTED;
        assertTrue(Interlocks.check(in).has("PLAN"));
        in.cfg.refuseFlaggedPads = false;
        assertFalse(Interlocks.check(in).has("PLAN"));
        in.machineHomed = false;
        assertTrue(Interlocks.check(in).has("MACHINE"));
        in = ok();
        in.cfg.keepaliveWindowMs = 50;
        assertTrue(Interlocks.check(in).has("CONFIG"));
        // an invalid profile is a configuration problem
        in = ok();
        in.cfg.profile.netStepsPerDot = 600;
        assertTrue(Interlocks.check(in).has("CONFIG"));
    }
}
