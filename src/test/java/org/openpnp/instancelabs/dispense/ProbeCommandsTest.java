package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The probe allowlist: the N1 vacuum and VAC1 lines of bench probe.py pass only the probe's gate;
 * the global gate still refuses every one of them, so neither a dispense stream nor the pressure hold
 * can switch N1's vacuum or write I2C.
 */
public class ProbeCommandsTest {
    static final List<String> PROBE_ONLY = Arrays.asList("M106", "M106 P1 S180", "M260 A112 B1 S1", "M260 A109",
            "M260 B48", "M260 B27", "M260 S1", "M260 A109 B6 S1", "M261 A109 B3 S1");

    @Test
    public void probeGateAllowsExactlyTheBenchLines() {
        Set<String> distinct = new LinkedHashSet<>();
        distinct.add(ControllerCommands.LEFT_PUMP_ON);
        distinct.add(ControllerCommands.LEFT_VALVE_ON);
        distinct.addAll(ControllerCommands.VAC1_INIT);
        distinct.addAll(ControllerCommands.VAC1_SELECT);
        distinct.add(ControllerCommands.VAC1_READ);
        assertEquals(new LinkedHashSet<>(PROBE_ONLY), distinct, "2 outputs + 6 distinct M260 + 1 M261");
        for (String l : PROBE_ONLY) {
            assertNull(ControllerCommands.checkProbe(l), l);
            assertTrue(ControllerCommands.isProbeLine(l), l);
            assertNotNull(ControllerCommands.check(l), "check() must still refuse " + l);
        }
        assertNull(ControllerCommands.checkProbe("m260  a109 ; comment"), "normalized like check()");
        // the probe's other lines are ordinary engine lines
        for (String l : Arrays.asList("G1 Z31.5000 F600", "G1 X327.9840 Y186.5330 F6000", "M400", "G4 P1500",
                "M204 S2000", "M107 P1", "M107 P0", "M410", "M18 B")) {
            assertNull(ControllerCommands.checkProbe(l), l);
            assertNull(ControllerCommands.check(l), l);
        }
    }

    @Test
    public void probeGateRefusesEveryOtherOutputAndI2cLine() {
        for (String l : Arrays.asList("M106 P1 S255", "M106 P3 S180", "M106 P0 S128", "M260 A112 B2 S1", "M260 A110 B1",
                "M261 A109 B1 S2", "M260 A109 B6", "M42 P13 S255", "M106 S180", "M0106 P1 S180")) {
            assertNotNull(ControllerCommands.checkProbe(l), l);
            assertThrows(IllegalStateException.class, () -> ControllerCommands.requireProbeAllowed(l), l);
        }
    }

    @Test
    public void dispenseValidatorNamesTheProbeLines() {
        DispenseConfig c = GcodeStreamTest.toyConfig();
        GcodeProgram q = new GcodeProgram();
        for (String text : Arrays.asList("M106 P1 S180", "M260 A109", "M106 P1 S255")) {
            q.lines.add(new GcodeProgram.Line(text, GcodeProgram.Marker.NONE, -9, null, 0, null));
        }
        List<String> problems = GcodeStreamValidator.validate(q, c, "B");
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("N1 vacuum and I2C lines belong to the probe"), problems.get(0));
        assertTrue(problems.get(1).contains("N1 vacuum and I2C lines belong to the probe"), problems.get(1));
        assertTrue(problems.get(2).contains("only M106 P2 S255 and M106 P3 S255"), problems.get(2));
    }
}
