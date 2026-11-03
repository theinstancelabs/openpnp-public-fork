package org.openpnp.instancelabs.dispense;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.junit.jupiter.api.Test;
import org.openpnp.model.Part;
import org.openpnp.spi.Nozzle;
import org.openpnp.spi.base.AbstractNozzle;

/** N1's raw mapper for the vacuum probe on the dispense SIM machine (N1 head offset -24.178, -63.91). */
public class N1MapperTest {
    @Test
    public void probeMapperMapsN1WithItsOwnRotationAxis() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            Exception e = assertThrows(Exception.class, () -> new OpenPnpRawMapper(sim.machine, sim.n1, "b"));
            assertTrue(e.getMessage().contains("rotation maps to axis"), e.getMessage());
            OpenPnpRawMapper m = OpenPnpRawMapper.forProbeNozzle(sim.machine, sim.n1, "b");
            RawPoint p = m.toRaw(303.806, 122.623, 5.5);
            assertEquals(303.806 + 24.178, p.x, 1e-6);
            assertEquals(122.623 + 63.91, p.y, 1e-6);
            assertEquals(5.5, p.z, 1e-9, "N1's Z is its raw Z");
            assertEquals("B", m.letterB(), "B stays the auger axis (M18 B, resync)");
            assertEquals("Z", m.letterZ());
            assertNull(m.probeNozzleProblem(sim.n2));
            double[] zone = m.zSafeZoneRaw();
            assertEquals(26.5, zone[0], 1e-9);
            assertEquals(36.5, zone[1], 1e-9);
        }
    }

    @Test
    public void theDispenserNozzleIsNoProbeAndN1StateIsRead() throws Exception {
        try (SimMachine sim = new SimMachine()) {
            OpenPnpRawMapper n2 = new OpenPnpRawMapper(sim.machine, sim.n2, "b");
            assertTrue(n2.probeNozzleProblem(sim.n2).startsWith("PROBE: 'N2' is the dispenser nozzle"));
            // N2 is the see-saw: its Z is transformed (z2 = 63 - z1)
            OpenPnpRawMapper n2probe = OpenPnpRawMapper.forProbeNozzle(sim.machine, sim.n2, "b");
            assertTrue(n2probe.probeNozzleProblem(sim.n1).contains("Z is transformed"),
                    n2probe.probeNozzleProblem(sim.n1));
            assertNull(VacuumProber.n1StateProblem(sim.n1), "N1 carries NT1 and no part");
            Method setPart = AbstractNozzle.class.getDeclaredMethod("setPart", Part.class);
            setPart.setAccessible(true);
            setPart.invoke(sim.n1, new Part("R5"));
            assertEquals("PROBE: OpenPnP says N1 holds a part (R5): discard it first",
                    VacuumProber.n1StateProblem(sim.n1));
            setPart.invoke(sim.n1, (Part) null);
            assertNull(VacuumProber.n1StateProblem(sim.n1));
        }
    }

    @Test
    public void noNozzleTipIsRefused() {
        Nozzle n = (Nozzle) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Nozzle.class},
                (proxy, method, args) -> method.getName().equals("getName") ? "N1" : null);
        assertEquals("PROBE: OpenPnP shows no nozzle tip on N1: load the N045 tip first",
                VacuumProber.n1StateProblem(n));
    }
}
